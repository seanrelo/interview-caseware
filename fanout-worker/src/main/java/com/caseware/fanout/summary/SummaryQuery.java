package com.caseware.fanout.summary;

import com.caseware.fanout.domain.EfId;
import com.caseware.fanout.port.FirmFlags;
import com.caseware.fanout.status.Caller;
import com.caseware.fanout.status.PendingUpdateQuery;
import com.caseware.fanout.status.StatusRow;
import com.caseware.fanout.status.UpdateStatus;
import java.util.Objects;
import java.util.Optional;

/**
 * Summary read facade for one EF. The firm comes from the caller and the EF lookup goes through
 * {@link PendingUpdateQuery}, so a foreign EF is empty (NFR-33). Any summary failure becomes
 * {@link SummaryView.Unavailable}; it never changes or fails the status (FR-42, NFR-30).
 */
public class SummaryQuery {
  private final PendingUpdateQuery status;
  private final FirmFlags flags;
  private final SummaryService service;

  public SummaryQuery(PendingUpdateQuery status, FirmFlags flags, SummaryService service) {
    this.status = Objects.requireNonNull(status, "status");
    this.flags = Objects.requireNonNull(flags, "flags");
    this.service = Objects.requireNonNull(service, "service");
  }

  /** Empty: flag off (NFR-41), unknown or foreign EF, or the EF has no pending update. */
  public Optional<SummaryView> summaryFor(Caller caller, EfId ef) {
    if (!flags.enabled(caller.firm())) {
      return Optional.empty();
    }
    Optional<StatusRow> row = status.detail(caller, ef);
    if (row.isEmpty()) {
      return Optional.empty();
    }
    StatusRow r = row.get();
    if (r.status() != UpdateStatus.UPDATE_PENDING
        || r.template().isEmpty()
        || r.baseline().isEmpty()
        || r.pendingTarget().isEmpty()) {
      return Optional.empty();
    }
    try {
      PairKey pair = new PairKey(r.template().get(), r.baseline().get(), r.pendingTarget().get());
      return Optional.of(
          switch (service.ensure(pair, JobReason.ON_DEMAND)) {
            case SummaryOutcome.Ready ready -> new SummaryView.Ready(ready.summary());
            case SummaryOutcome.Generating g -> new SummaryView.Generating();
            case SummaryOutcome.Failed f -> new SummaryView.Unavailable();
          });
    } catch (RuntimeException e) {
      return Optional.of(new SummaryView.Unavailable());
    }
  }
}
