package com.caseware.fanout.limiter;

import com.caseware.fanout.app.CallGate;
import com.caseware.fanout.domain.FirmId;
import com.caseware.fanout.domain.WorkItem;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Global in-flight cap with AIMD (NFR-8, NFR-9) and per-firm fairness (NFR-11), ADR 0007.
 *
 * <ul>
 *   <li>Cap: a permit is granted only while in-flight is below the current limit. All state changes
 *       happen under one lock, so the count never exceeds the limit at acquisition time. After a
 *       decrease, in-flight may sit above the new limit until calls finish; no new permit is
 *       granted meanwhile.
 *   <li>Decrease: the limit is halved (floor, min {@code minInFlight}) on a THROTTLED outcome, or
 *       when the error rate over {@code errorWindow} exceeds the threshold (THROTTLED counts as an
 *       error). Decreases are spaced by {@code decreaseCooldown}.
 *   <li>Increase: +1 after as many consecutive-healthy successes as the current limit, up to the
 *       configured maximum (about one step per round trip).
 *   <li>Fairness: see {@link FirmFairness}; NFR-11 vs NFR-13 conflict for a large firm under
 *       contention is accepted in ADR 0007, no workaround here.
 * </ul>
 *
 * <p>Time comes only from the injected {@link Clock}; nothing sleeps. NFR-24 (reads keep their
 * budget) is a consequence of this cap on writes and is not measurable in memory.
 */
public class AdaptiveLimiter implements CallGate {

  private record Sample(Instant at, boolean error) {}

  private final LimiterProperties props;
  private final Clock clock;
  private final FirmFairness fairness;
  private final Object lock = new Object();
  private final Deque<Sample> window = new ArrayDeque<>();

  private int limit;
  private int inFlight;
  private int errorsInWindow;
  private int successesSinceChange;
  private Instant lastDecrease = Instant.MIN;

  public AdaptiveLimiter(LimiterProperties props, Clock clock, FirmFairness fairness) {
    this.props = Objects.requireNonNull(props);
    this.clock = Objects.requireNonNull(clock);
    this.fairness = Objects.requireNonNull(fairness);
    this.limit = props.maxInFlight();
  }

  @Override
  public Optional<Permit> tryAcquire(WorkItem item) {
    FirmId firm = item.firm();
    boolean needContention;
    synchronized (lock) {
      if (inFlight >= limit) {
        return Optional.empty();
      }
      if (!fairness.atCap(firm, limit)) {
        return Optional.of(grant(firm));
      }
      needContention = true;
    }
    // At the firm cap: the cap only binds if someone else is waiting. Query outside the lock.
    boolean contended = needContention && fairness.othersQueued(firm);
    synchronized (lock) {
      if (inFlight >= limit || (contended && fairness.atCap(firm, limit))) {
        return Optional.empty();
      }
      return Optional.of(grant(firm));
    }
  }

  private Permit grant(FirmId firm) {
    inFlight++;
    fairness.acquire(firm);
    AtomicBoolean released = new AtomicBoolean();
    return outcome -> {
      if (released.compareAndSet(false, true)) {
        onRelease(firm, outcome);
      }
    };
  }

  private void onRelease(FirmId firm, Outcome outcome) {
    synchronized (lock) {
      inFlight--;
      fairness.release(firm);
      Instant now = clock.instant();
      evict(now);
      boolean error = outcome != Outcome.SUCCESS;
      window.addLast(new Sample(now, error));
      if (error) {
        errorsInWindow++;
      }
      boolean tooManyErrors =
          window.size() >= props.errorMinSamples()
              && (double) errorsInWindow / window.size() > props.errorRateThreshold();
      if (outcome == Outcome.THROTTLED || tooManyErrors) {
        decrease(now);
      } else if (outcome == Outcome.SUCCESS && ++successesSinceChange >= limit) {
        successesSinceChange = 0;
        limit = Math.min(props.maxInFlight(), limit + 1);
      }
    }
  }

  private void decrease(Instant now) {
    successesSinceChange = 0;
    if (lastDecrease.plus(props.decreaseCooldown()).isAfter(now)) {
      return;
    }
    lastDecrease = now;
    limit = Math.max(props.minInFlight(), limit / 2);
    // Judge the new limit on fresh evidence, not on the outcomes that caused the cut.
    window.clear();
    errorsInWindow = 0;
  }

  private void evict(Instant now) {
    Instant cutoff = now.minus(props.errorWindow());
    while (!window.isEmpty() && window.peekFirst().at().isBefore(cutoff)) {
      if (window.removeFirst().error()) {
        errorsInWindow--;
      }
    }
  }

  public int limit() {
    synchronized (lock) {
      return limit;
    }
  }

  public int inFlight() {
    synchronized (lock) {
      return inFlight;
    }
  }

  public int firmInFlight(FirmId firm) {
    synchronized (lock) {
      return fairness.inFlight(firm);
    }
  }

  /**
   * Firms the dispatcher should not lease for right now: at the firm cap while another firm has
   * queued work. Lease-time filter only; {@link #tryAcquire} remains the hard enforcement.
   */
  public Set<FirmId> saturatedFirms() {
    Set<FirmId> atCap;
    synchronized (lock) {
      atCap = fairness.firmsAtCap(limit);
    }
    if (atCap.isEmpty()) {
      return Set.of();
    }
    Set<FirmId> queued = fairness.queued();
    Set<FirmId> out = new HashSet<>();
    for (FirmId f : atCap) {
      for (FirmId q : queued) {
        if (!q.equals(f)) {
          out.add(f);
          break;
        }
      }
    }
    return out;
  }
}
