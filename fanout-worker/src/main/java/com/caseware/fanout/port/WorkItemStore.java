package com.caseware.fanout.port;

import com.caseware.fanout.domain.FirmId;
import com.caseware.fanout.domain.ItemState;
import com.caseware.fanout.domain.PublishCounts;
import com.caseware.fanout.domain.WorkItem;
import com.caseware.fanout.domain.WorkKey;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Durable work items of one regional cell (ADR 0006). */
public interface WorkItemStore {
  /** Conditional put on the key. True if created, false if the key already existed (FR-15). */
  boolean createIfAbsent(WorkItem item);

  /**
   * Read by key. Added to the brief's list: the handler needs it to count items that already carry
   * its publishId (ADR 0006 clarification 1).
   */
  Optional<WorkItem> find(WorkKey key);

  /**
   * Leases up to max items that are PENDING with notBefore <= now, or LEASED with an expired lease
   * (restart safety, NFR-10), skipping excluded firms. Returned items are LEASED with a fresh token.
   */
  List<WorkItem> leaseReady(Instant now, Duration lease, int max, Set<FirmId> excludedFirms);

  /** Firms that have work ready to lease at {@code now} (for work-conserving fairness). */
  Set<FirmId> firmsWithQueuedWork(Instant now);

  /**
   * Moves a leased item to a terminal state (DONE, SKIPPED, DEAD_LETTERED). The skip reason is
   * taken from {@code leased}. False if the lease token no longer matches (stale holder).
   */
  boolean complete(WorkItem leased, ItemState terminal);

  /**
   * Counts the items carrying {@code publishId} by state; PENDING and LEASED are non-terminal. The
   * source of truth for FR-21 completion.
   */
  PublishCounts countByPublish(String publishId);

  /** Returns a leased item to PENDING with new attempts and notBefore. False if the lease is stale. */
  boolean reschedule(WorkItem leased, int attempts, Instant notBefore);
}
