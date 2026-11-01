package com.caseware.fanout.limiter;

import com.caseware.fanout.domain.FirmId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Work-conserving per-firm cap (NFR-11, ADR 0007). A firm may hold at most
 * {@code max(1, floor(share * limit))} in-flight calls, but only while some OTHER firm has queued
 * work. A lone firm may use the whole limit.
 *
 * <p>Known conflict, no workaround (ADR 0007): NFR-11 against NFR-13. A firm with 40,000 EFs under
 * contention is held to 25% of the limit and may need longer than the 10 s indicator target. That
 * is the accepted tradeoff and is to be stated at design review.
 *
 * <p>Not thread-safe: the {@link AdaptiveLimiter} calls it under its lock. {@code queuedFirms} may
 * be slow (a store query), so the limiter calls {@link #othersQueued} outside its lock.
 */
public class FirmFairness {

  private final double share;
  private final Supplier<Set<FirmId>> queuedFirms;
  private final Map<FirmId, Integer> inFlight = new HashMap<>();

  public FirmFairness(double share, Supplier<Set<FirmId>> queuedFirms) {
    this.share = share;
    this.queuedFirms = queuedFirms;
  }

  public int cap(int limit) {
    return Math.max(1, (int) Math.floor(share * limit));
  }

  public int inFlight(FirmId firm) {
    return inFlight.getOrDefault(firm, 0);
  }

  public boolean atCap(FirmId firm, int limit) {
    return inFlight(firm) >= cap(limit);
  }

  void acquire(FirmId firm) {
    inFlight.merge(firm, 1, Integer::sum);
  }

  void release(FirmId firm) {
    inFlight.computeIfPresent(firm, (f, n) -> n <= 1 ? null : n - 1);
  }

  /** True if any firm other than {@code firm} has work ready to lease. Queries the store. */
  public boolean othersQueued(FirmId firm) {
    for (FirmId f : queuedFirms.get()) {
      if (!f.equals(firm)) {
        return true;
      }
    }
    return false;
  }

  /** Snapshot of the in-flight firms at or over the cap; caller filters by contention. */
  Set<FirmId> firmsAtCap(int limit) {
    int cap = cap(limit);
    Set<FirmId> out = new HashSet<>();
    inFlight.forEach((f, n) -> {
      if (n >= cap) {
        out.add(f);
      }
    });
    return out;
  }

  Set<FirmId> queued() {
    return queuedFirms.get();
  }
}
