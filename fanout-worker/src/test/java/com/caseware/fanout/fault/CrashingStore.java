package com.caseware.fanout.fault;

import com.caseware.fanout.domain.FirmId;
import com.caseware.fanout.domain.ItemState;
import com.caseware.fanout.domain.WorkItem;
import com.caseware.fanout.domain.WorkKey;
import com.caseware.fanout.port.WorkItemStore;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Crash-injecting decorator for {@link WorkItemStore}. Writes are createIfAbsent, leaseReady,
 * complete, reschedule; reads (find, countByPublish, firmsWithQueuedWork) never crash. After {@code k} successful
 * writes the process is "dead": every further write throws {@link SimulatedCrashException}
 * until {@link #revive()}. The wrapped store keeps its state, which models a durable store
 * surviving a worker restart.
 *
 * <p>With {@code applyThenCrash} the first failing write is applied to the delegate before the
 * exception (acknowledgement lost); later writes are not applied.
 */
public class CrashingStore implements WorkItemStore {

  private final WorkItemStore delegate;
  private final boolean applyThenCrash;
  private final AtomicLong remaining;
  private final AtomicLong writes = new AtomicLong();
  private volatile boolean dead;
  private volatile boolean lostAckUsed;

  public CrashingStore(WorkItemStore delegate, long crashAfterWrites, boolean applyThenCrash) {
    this.delegate = delegate;
    this.remaining = new AtomicLong(crashAfterWrites);
    this.applyThenCrash = applyThenCrash;
  }

  public CrashingStore(WorkItemStore delegate, long crashAfterWrites) {
    this(delegate, crashAfterWrites, false);
  }

  /** Worker restart: writes work again, with a fresh budget (Long.MAX_VALUE for never). */
  public void revive(long newBudget) {
    remaining.set(newBudget);
    dead = false;
    lostAckUsed = false;
  }

  public void revive() {
    revive(Long.MAX_VALUE);
  }

  public boolean crashed() {
    return dead;
  }

  /** Writes that completed against the delegate (including an applied-then-crashed one). */
  public long successfulWrites() {
    return writes.get();
  }

  private boolean admit() {
    if (dead) {
      return false;
    }
    if (remaining.getAndUpdate(v -> v == Long.MAX_VALUE ? v : Math.max(v - 1, -1)) > 0) {
      return true;
    }
    dead = true;
    return false;
  }

  private <T> T write(java.util.function.Supplier<T> op, String name) {
    if (admit()) {
      T r = op.get();
      writes.incrementAndGet();
      return r;
    }
    if (applyThenCrash && !lostAckUsed) {
      lostAckUsed = true;
      op.get();
      writes.incrementAndGet();
    }
    throw new SimulatedCrashException("crash during " + name);
  }

  @Override
  public boolean createIfAbsent(WorkItem item) {
    return write(() -> delegate.createIfAbsent(item), "createIfAbsent");
  }

  @Override
  public Optional<WorkItem> find(WorkKey key) {
    return delegate.find(key);
  }

  @Override
  public com.caseware.fanout.domain.PublishCounts countByPublish(String publishId) {
    return delegate.countByPublish(publishId);
  }

  @Override
  public List<WorkItem> leaseReady(
      Instant now, Duration lease, int max, Set<FirmId> excludedFirms) {
    return write(() -> delegate.leaseReady(now, lease, max, excludedFirms), "leaseReady");
  }

  @Override
  public Set<FirmId> firmsWithQueuedWork(Instant now) {
    return delegate.firmsWithQueuedWork(now);
  }

  @Override
  public boolean complete(WorkItem leased, ItemState terminal) {
    return write(() -> delegate.complete(leased, terminal), "complete");
  }

  @Override
  public boolean reschedule(WorkItem leased, int attempts, Instant notBefore) {
    return write(() -> delegate.reschedule(leased, attempts, notBefore), "reschedule");
  }
}
