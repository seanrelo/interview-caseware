package com.caseware.fanout.adapter.memory;

import com.caseware.fanout.domain.FirmId;
import com.caseware.fanout.domain.ItemState;
import com.caseware.fanout.domain.PublishCounts;
import com.caseware.fanout.domain.WorkItem;
import com.caseware.fanout.domain.WorkKey;
import com.caseware.fanout.port.WorkItemStore;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Thread-safe store. Every mutation is an atomic per-key compute or a store-wide lock. */
public class InMemoryWorkItemStore implements WorkItemStore {

  private final Map<WorkKey, WorkItem> items = new ConcurrentHashMap<>();
  private final AtomicLong tokens = new AtomicLong();
  private final Object leaseLock = new Object();

  @Override
  public boolean createIfAbsent(WorkItem item) {
    return items.putIfAbsent(item.key(), item) == null;
  }

  @Override
  public Optional<WorkItem> find(WorkKey key) {
    return Optional.ofNullable(items.get(key));
  }

  @Override
  public List<WorkItem> leaseReady(
      Instant now, Duration lease, int max, Set<FirmId> excludedFirms) {
    List<WorkItem> leased = new ArrayList<>();
    if (max <= 0) {
      return leased;
    }
    synchronized (leaseLock) {
      for (WorkKey key : items.keySet()) {
        if (leased.size() == max) {
          break;
        }
        WorkItem[] taken = new WorkItem[1];
        items.computeIfPresent(
            key,
            (k, it) -> {
              if (!ready(it, now) || excludedFirms.contains(it.firm())) {
                return it;
              }
              WorkItem l =
                  new WorkItem(
                      it.key(),
                      it.firm(),
                      it.publishId(),
                      ItemState.LEASED,
                      it.attempts(),
                      it.notBefore(),
                      now.plus(lease),
                      "lease-" + tokens.incrementAndGet(),
                      null);
              taken[0] = l;
              return l;
            });
        if (taken[0] != null) {
          leased.add(taken[0]);
        }
      }
    }
    return leased;
  }

  @Override
  public Set<FirmId> firmsWithQueuedWork(Instant now) {
    Set<FirmId> firms = new HashSet<>();
    for (WorkItem it : items.values()) {
      if (ready(it, now)) {
        firms.add(it.firm());
      }
    }
    return firms;
  }

  @Override
  public boolean complete(WorkItem leased, ItemState terminal) {
    if (!terminal.terminal()) {
      throw new IllegalArgumentException("not a terminal state: " + terminal);
    }
    return replaceIfHeld(
        leased,
        it ->
            new WorkItem(
                it.key(),
                it.firm(),
                it.publishId(),
                terminal,
                it.attempts(),
                it.notBefore(),
                null,
                null,
                terminal == ItemState.SKIPPED ? leased.skipReason() : null));
  }

  @Override
  public PublishCounts countByPublish(String publishId) {
    long done = 0;
    long skipped = 0;
    long dead = 0;
    long open = 0;
    for (WorkItem it : items.values()) {
      if (!publishId.equals(it.publishId())) {
        continue;
      }
      switch (it.state()) {
        case DONE -> done++;
        case SKIPPED -> skipped++;
        case DEAD_LETTERED -> dead++;
        default -> open++;
      }
    }
    return new PublishCounts(done, skipped, dead, open);
  }

  @Override
  public boolean reschedule(WorkItem leased, int attempts, Instant notBefore) {
    return replaceIfHeld(
        leased,
        it ->
            new WorkItem(
                it.key(),
                it.firm(),
                it.publishId(),
                ItemState.PENDING,
                attempts,
                notBefore,
                null,
                null,
                null));
  }

  private boolean replaceIfHeld(
      WorkItem leased, java.util.function.UnaryOperator<WorkItem> update) {
    boolean[] done = new boolean[1];
    items.computeIfPresent(
        leased.key(),
        (k, it) -> {
          if (it.state() == ItemState.LEASED
              && it.leaseToken() != null
              && it.leaseToken().equals(leased.leaseToken())) {
            done[0] = true;
            return update.apply(it);
          }
          return it;
        });
    return done[0];
  }

  private static boolean ready(WorkItem it, Instant now) {
    return switch (it.state()) {
      case PENDING -> !it.notBefore().isAfter(now);
      case LEASED -> it.leaseUntil() != null && !it.leaseUntil().isAfter(now);
      default -> false;
    };
  }
}
