package com.caseware.fanout.app;

import com.caseware.fanout.domain.FirmId;
import com.caseware.fanout.domain.WorkItem;
import com.caseware.fanout.port.WorkItemStore;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Lease loop. {@link #runOnce()} and {@link #drain()} are safe to call from many threads on one
 * store: leasing is atomic in the store, so no item is handed to two workers. Nothing here sleeps;
 * the caller (scheduler, test) decides when to call again.
 */
public class Dispatcher {

  private final WorkItemStore store;
  private final ItemProcessor processor;
  private final Clock clock;
  private final Duration leaseDuration;
  private final int batchSize;
  private final Supplier<Set<FirmId>> excludedFirms;

  public Dispatcher(
      WorkItemStore store,
      ItemProcessor processor,
      Clock clock,
      Duration leaseDuration,
      int batchSize) {
    this(store, processor, clock, leaseDuration, batchSize, Set::of);
  }

  /** {@code excludedFirms} is the fairness seam (T5, NFR-11). */
  public Dispatcher(
      WorkItemStore store,
      ItemProcessor processor,
      Clock clock,
      Duration leaseDuration,
      int batchSize,
      Supplier<Set<FirmId>> excludedFirms) {
    if (batchSize <= 0 || leaseDuration.isZero() || leaseDuration.isNegative()) {
      throw new IllegalArgumentException("batchSize and leaseDuration must be positive");
    }
    this.store = Objects.requireNonNull(store);
    this.processor = Objects.requireNonNull(processor);
    this.clock = Objects.requireNonNull(clock);
    this.leaseDuration = leaseDuration;
    this.batchSize = batchSize;
    this.excludedFirms = Objects.requireNonNull(excludedFirms);
  }

  /** Leases one batch and processes it. Returns the number of items leased. Never throws for a downstream failure. */
  public int runOnce() {
    return pass()[0];
  }

  /**
   * Repeats passes until nothing is ready or every leased item was deferred for lack of capacity.
   * Returns the number of items leased.
   */
  public int drain() {
    int total = 0;
    while (true) {
      int[] r = pass();
      total += r[0];
      if (r[0] == 0 || r[0] == r[1]) {
        return total;
      }
    }
  }

  /** Returns {leased, deferred}. */
  private int[] pass() {
    List<WorkItem> batch =
        store.leaseReady(clock.instant(), leaseDuration, batchSize, excludedFirms.get());
    int deferred = 0;
    for (WorkItem item : batch) {
      if (processor.process(item) == ItemProcessor.Outcome.DEFERRED) {
        deferred++;
      }
    }
    return new int[] {batch.size(), deferred};
  }
}
