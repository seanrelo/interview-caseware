package com.caseware.fanout.app;

import com.caseware.fanout.domain.DeadLetter;
import com.caseware.fanout.domain.ItemState;
import com.caseware.fanout.domain.WorkItem;
import com.caseware.fanout.limiter.RetryPolicy;
import com.caseware.fanout.port.DeadLetterSink;
import com.caseware.fanout.port.DownstreamException;
import com.caseware.fanout.port.PendingUpdateMarker;
import com.caseware.fanout.port.WorkItemStore;
import java.time.Clock;
import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * FR-18 and FR-19 (ADR 0007, ADR 0006). {@code WorkItem.attempts} counts failed calls so far.
 * Throttle and plain errors are retried identically; the AIMD limiter reacts to the difference, not
 * this handler.
 *
 * <p>On failure number n: if n is below the policy maximum the item is rescheduled with attempts=n
 * and notBefore = now + jittered backoff. Otherwise it is dead-lettered: DLQ entry, then
 * {@code markUnknown} (monotonic, never overwrites a newer pending target), then terminal
 * DEAD_LETTERED. The EF is never recorded as up to date. The order makes a crash retry-safe: a
 * lease that expires before the terminal CAS re-runs the item, which may add a duplicate DLQ entry
 * (at-least-once) but never loses it.
 *
 * <p>Alert rule (NFR-39, to agree): alarm when {@code dlq.depth} stays above 0 for 15 minutes.
 * Config only, not testable here.
 */
public class RetryingFailureHandler implements FailureHandler {

  private final WorkItemStore store;
  private final PendingUpdateMarker marker;
  private final DeadLetterSink deadLetters;
  private final RetryPolicy policy;
  private final Clock clock;
  private final RandomGenerator random;
  private final TerminalListener listener;

  public RetryingFailureHandler(
      WorkItemStore store,
      PendingUpdateMarker marker,
      DeadLetterSink deadLetters,
      RetryPolicy policy,
      Clock clock,
      RandomGenerator random,
      TerminalListener listener) {
    this.store = Objects.requireNonNull(store);
    this.marker = Objects.requireNonNull(marker);
    this.deadLetters = Objects.requireNonNull(deadLetters);
    this.policy = Objects.requireNonNull(policy);
    this.clock = Objects.requireNonNull(clock);
    this.random = Objects.requireNonNull(random);
    this.listener = Objects.requireNonNull(listener);
  }

  @Override
  public void onFailure(WorkItem leased, DownstreamException failure) {
    int failed = leased.attempts() + 1;
    if (!policy.exhausted(failed)) {
      long delayMs;
      synchronized (random) { // RandomGenerator implementations are not all thread-safe
        delayMs = policy.delay(failed, random).toMillis();
      }
      store.reschedule(leased, failed, clock.instant().plusMillis(delayMs));
      return;
    }
    String reason = "retries exhausted after " + failed + " attempts: " + failure.getMessage();
    deadLetters.put(new DeadLetter(leased.key(), leased.firm(), failed, reason, clock.instant()));
    marker.markUnknown(leased.key(), reason);
    if (store.complete(leased, ItemState.DEAD_LETTERED)) {
      listener.onTerminal(leased, ItemState.DEAD_LETTERED);
    }
  }
}
