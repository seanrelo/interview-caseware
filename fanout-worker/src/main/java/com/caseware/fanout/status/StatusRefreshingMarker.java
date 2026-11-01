package com.caseware.fanout.status;

import com.caseware.fanout.domain.EfRef;
import com.caseware.fanout.domain.Region;
import com.caseware.fanout.domain.WorkKey;
import com.caseware.fanout.graph.EligibleResult;
import com.caseware.fanout.port.DownstreamException;
import com.caseware.fanout.port.DownstreamThrottledException;
import com.caseware.fanout.port.MarkResult;
import com.caseware.fanout.port.PendingUpdateMarker;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Decorator: delegates first, then re-derives the status row of the key's EF. The row is the truth;
 * the delegate's state is a hint. Once the delegate succeeded, nothing thrown by the row refresh
 * (missing firm lookup, store failure) escapes: it is counted and reconciliation repairs the row
 * (NFR-28). A row that could not be written is never UP_TO_DATE by omission: the prior row stays
 * until repaired, which is why failures are counted for alarms.
 */
public final class StatusRefreshingMarker implements PendingUpdateMarker {

  private final PendingUpdateMarker delegate;
  private final StatusService service;
  private final Region region;
  private final AtomicLong refreshFailures = new AtomicLong();
  private final AtomicLong unresolvedFirms = new AtomicLong();

  public StatusRefreshingMarker(PendingUpdateMarker delegate, StatusService service, Region region) {
    this.delegate = Objects.requireNonNull(delegate);
    this.service = Objects.requireNonNull(service);
    this.region = Objects.requireNonNull(region);
  }

  @Override
  public MarkResult record(WorkKey key) throws DownstreamThrottledException, DownstreamException {
    MarkResult r = delegate.record(key);
    after(key, false);
    return r;
  }

  @Override
  public void markUnknown(WorkKey key, String internalReason) {
    delegate.markUnknown(key, internalReason);
    after(key, true);
  }

  @Override
  public MarkResult reevaluate(WorkKey withdrawnTarget, EligibleResult result) {
    MarkResult r = delegate.reevaluate(withdrawnTarget, result);
    after(withdrawnTarget, false);
    return r;
  }

  /** Row refreshes that threw after the delegate succeeded. */
  public long refreshFailures() {
    return refreshFailures.get();
  }

  /** Refreshes skipped because the EF's firm could not be resolved. */
  public long unresolvedFirms() {
    return unresolvedFirms.get();
  }

  private void after(WorkKey key, boolean unknown) {
    try {
      Optional<EfRef> ef = service.firmOf(key.ef());
      if (ef.isEmpty()) {
        unresolvedFirms.incrementAndGet();
        return;
      }
      if (unknown) {
        service.markUnknown(region, ef.get(), key.templateId(), key.targetVersion());
      } else {
        service.refresh(region, ef.get());
      }
    } catch (Exception e) {
      refreshFailures.incrementAndGet();
    }
  }
}
