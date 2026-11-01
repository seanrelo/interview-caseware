package com.caseware.fanout.app;

import com.caseware.fanout.domain.ItemState;
import com.caseware.fanout.domain.SkipReason;
import com.caseware.fanout.domain.WorkItem;
import com.caseware.fanout.port.DownstreamException;
import com.caseware.fanout.port.DownstreamThrottledException;
import com.caseware.fanout.port.FirmFlags;
import com.caseware.fanout.port.PendingUpdateMarker;
import com.caseware.fanout.port.WorkItemStore;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

/**
 * Processes one leased work item (FR-16, FR-17). Stateless and thread-safe. Never throws for a
 * downstream failure (NFR-30). A completed item is never re-invoked because the store only leases
 * non-terminal items and completion is a compare-and-set on the lease token (NFR-10).
 *
 * <p>Crash between marker write and completion: the lease expires, the item is re-leased and the
 * monotonic marker answers UNCHANGED, so the final state is equal (FR-17, NFR-12).
 */
public class ItemProcessor {

  public enum Outcome {
    DONE,
    SKIPPED_FLAG_OFF,
    SKIPPED_SUPERSEDED,
    /** Call failed; the failure handler (or lease expiry) owns the next step. */
    FAILED,
    /** No capacity permit; rescheduled without consuming an attempt. */
    DEFERRED,
    /** Item was not in a leasable state or the lease was lost to another worker. */
    STALE
  }

  private final WorkItemStore store;
  private final PendingUpdateMarker marker;
  private final FirmFlags flags;
  private final Clock clock;
  private final SupersessionCheck supersession;
  private final FailureHandler failureHandler;
  private final CallGate gate;
  private final TerminalListener listener;

  public ItemProcessor(
      WorkItemStore store, PendingUpdateMarker marker, FirmFlags flags, Clock clock) {
    this(
        store,
        marker,
        flags,
        clock,
        SupersessionCheck.NEVER,
        FailureHandler.NONE,
        CallGate.UNLIMITED,
        TerminalListener.NONE);
  }

  public ItemProcessor(
      WorkItemStore store,
      PendingUpdateMarker marker,
      FirmFlags flags,
      Clock clock,
      SupersessionCheck supersession,
      FailureHandler failureHandler,
      CallGate gate,
      TerminalListener listener) {
    this.store = Objects.requireNonNull(store);
    this.marker = Objects.requireNonNull(marker);
    this.flags = Objects.requireNonNull(flags);
    this.clock = Objects.requireNonNull(clock);
    this.supersession = Objects.requireNonNull(supersession);
    this.failureHandler = Objects.requireNonNull(failureHandler);
    this.gate = Objects.requireNonNull(gate);
    this.listener = Objects.requireNonNull(listener);
  }

  public Outcome process(WorkItem leased) {
    if (leased.state() != ItemState.LEASED) {
      return Outcome.STALE;
    }
    if (!flags.enabled(leased.firm())) {
      return finish(leased.withSkipReason(SkipReason.FLAG_OFF), ItemState.SKIPPED, Outcome.SKIPPED_FLAG_OFF);
    }
    if (supersession.superseded(leased)) {
      return finish(
          leased.withSkipReason(SkipReason.SUPERSEDED), ItemState.SKIPPED, Outcome.SKIPPED_SUPERSEDED);
    }
    Optional<CallGate.Permit> permit = gate.tryAcquire(leased);
    if (permit.isEmpty()) {
      store.reschedule(leased, leased.attempts(), clock.instant());
      return Outcome.DEFERRED;
    }
    try {
      marker.record(leased.key()); // APPLIED and UNCHANGED are both success (FR-17)
    } catch (DownstreamException e) {
      permit.get().release(
          e instanceof DownstreamThrottledException
              ? CallGate.Outcome.THROTTLED
              : CallGate.Outcome.ERROR);
      return failed(leased, e);
    } catch (RuntimeException e) {
      permit.get().release(CallGate.Outcome.ERROR);
      return failed(leased, new DownstreamException("unexpected marker failure", e));
    }
    permit.get().release(CallGate.Outcome.SUCCESS);
    return finish(leased, ItemState.DONE, Outcome.DONE);
  }

  private Outcome failed(WorkItem leased, DownstreamException failure) {
    try {
      failureHandler.onFailure(leased, failure);
    } catch (RuntimeException ignored) {
      // NFR-30: never propagate. The item stays LEASED and is re-leased after expiry.
    }
    return Outcome.FAILED;
  }

  private Outcome finish(WorkItem leased, ItemState terminal, Outcome outcome) {
    if (!store.complete(leased, terminal)) {
      return Outcome.STALE;
    }
    try {
      listener.onTerminal(leased, terminal);
    } catch (RuntimeException ignored) {
      // The listener only feeds metrics; completion is derived from the store (FR-21).
    }
    return outcome;
  }
}
