package com.caseware.fanout.fault;

import com.caseware.fanout.domain.WorkKey;
import com.caseware.fanout.port.DownstreamException;
import com.caseware.fanout.port.DownstreamThrottledException;
import com.caseware.fanout.port.MarkResult;
import com.caseware.fanout.port.PendingUpdateMarker;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Fault-injecting decorator for {@link PendingUpdateMarker}. Deterministic: scripted failures are
 * counted, random failures use a seeded {@link Random}. No sleeping: latency is a caller-supplied
 * hook (e.g. advance a fake clock, block on a latch).
 *
 * <p>Failures are thrown BEFORE the delegate is invoked (the write did not happen). Use {@link
 * #loseAckForNext(int)} to model "write applied, response lost".
 */
public class FlakyMarker implements PendingUpdateMarker {

  private final PendingUpdateMarker delegate;
  private final Random random;
  private final Map<WorkKey, AtomicInteger> calls = new ConcurrentHashMap<>();
  private final Map<WorkKey, AtomicInteger> delegated = new ConcurrentHashMap<>();
  private final Set<WorkKey> alwaysFail = ConcurrentHashMap.newKeySet();
  private final AtomicInteger failNext = new AtomicInteger();
  private final AtomicInteger throttleNext = new AtomicInteger();
  private final AtomicInteger loseAckNext = new AtomicInteger();
  private final AtomicLong total = new AtomicLong();
  private final AtomicInteger inFlight = new AtomicInteger();
  private final AtomicInteger maxInFlight = new AtomicInteger();
  private volatile double failureRate;
  private volatile boolean allThrottled;
  private volatile Consumer<WorkKey> latencyHook = k -> {};

  public FlakyMarker(PendingUpdateMarker delegate, long seed) {
    this.delegate = delegate;
    this.random = new Random(seed);
  }

  /** The next n record calls (any key) fail with DownstreamException. */
  public FlakyMarker failNext(int n) {
    failNext.set(n);
    return this;
  }

  /** The next n record calls (any key) fail with DownstreamThrottledException. */
  public FlakyMarker throttleNext(int n) {
    throttleNext.set(n);
    return this;
  }

  /** While true every record call is throttled. */
  public FlakyMarker throttleAll(boolean on) {
    allThrottled = on;
    return this;
  }

  /** Every record call for this key fails until {@link #heal(WorkKey)}. */
  public FlakyMarker failAlways(WorkKey key) {
    alwaysFail.add(key);
    return this;
  }

  public FlakyMarker heal(WorkKey key) {
    alwaysFail.remove(key);
    return this;
  }

  /** Each record call fails with this probability, drawn from the seeded generator. */
  public FlakyMarker failureRate(double rate) {
    failureRate = rate;
    return this;
  }

  /** The next n record calls reach the delegate (write applied) and then throw. */
  public FlakyMarker loseAckForNext(int n) {
    loseAckNext.set(n);
    return this;
  }

  /** Invoked at the start of every record call, before any failure decision. */
  public FlakyMarker latency(Consumer<WorkKey> hook) {
    latencyHook = hook;
    return this;
  }

  public int callsFor(WorkKey key) {
    AtomicInteger c = calls.get(key);
    return c == null ? 0 : c.get();
  }

  /** Calls that reached the delegate for this key. */
  public int delegatedFor(WorkKey key) {
    AtomicInteger c = delegated.get(key);
    return c == null ? 0 : c.get();
  }

  public long totalCalls() {
    return total.get();
  }

  public int maxObservedInFlight() {
    return maxInFlight.get();
  }

  @Override
  public MarkResult record(WorkKey key) {
    total.incrementAndGet();
    calls.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
    int now = inFlight.incrementAndGet();
    maxInFlight.accumulateAndGet(now, Math::max);
    try {
      latencyHook.accept(key);
      if (allThrottled || take(throttleNext)) {
        throw new DownstreamThrottledException("injected throttle");
      }
      if (alwaysFail.contains(key) || take(failNext) || randomFailure()) {
        throw new DownstreamException("injected failure");
      }
      delegated.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
      MarkResult r = delegate.record(key);
      if (take(loseAckNext)) {
        throw new DownstreamException("injected lost ack");
      }
      return r;
    } finally {
      inFlight.decrementAndGet();
    }
  }

  @Override
  public void markUnknown(WorkKey key, String internalReason) {
    delegate.markUnknown(key, internalReason);
  }

  @Override
  public MarkResult reevaluate(
      WorkKey withdrawnTarget, com.caseware.fanout.graph.EligibleResult result) {
    return delegate.reevaluate(withdrawnTarget, result);
  }

  private boolean randomFailure() {
    double rate = failureRate;
    if (rate <= 0) {
      return false;
    }
    synchronized (random) {
      return random.nextDouble() < rate;
    }
  }

  private static boolean take(AtomicInteger counter) {
    while (true) {
      int v = counter.get();
      if (v <= 0) {
        return false;
      }
      if (counter.compareAndSet(v, v - 1)) {
        return true;
      }
    }
  }
}
