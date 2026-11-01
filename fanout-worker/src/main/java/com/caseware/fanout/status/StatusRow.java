package com.caseware.fanout.status;

import com.caseware.fanout.domain.EfId;
import com.caseware.fanout.domain.FirmId;
import com.caseware.fanout.domain.TemplateId;
import com.caseware.fanout.domain.VersionId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Materialised status of one EF, partitioned by firm (ADR 0008). */
public record StatusRow(
    FirmId firm,
    EfId ef,
    Optional<TemplateId> template,
    UpdateStatus status,
    Optional<VersionId> baseline,
    Optional<VersionId> pendingTarget,
    Instant updatedAt,
    StatusSource source) {

  public StatusRow {
    Objects.requireNonNull(firm, "firm");
    Objects.requireNonNull(ef, "ef");
    Objects.requireNonNull(template, "template");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(baseline, "baseline");
    Objects.requireNonNull(pendingTarget, "pendingTarget");
    Objects.requireNonNull(updatedAt, "updatedAt");
    Objects.requireNonNull(source, "source");
    if (pendingTarget.isPresent() != (status == UpdateStatus.UPDATE_PENDING)) {
      throw new IllegalArgumentException("pendingTarget is present if and only if UPDATE_PENDING");
    }
    if (source == StatusSource.FANOUT_UNKNOWN && status != UpdateStatus.UNKNOWN) {
      throw new IllegalArgumentException("FANOUT_UNKNOWN rows are UNKNOWN");
    }
  }
}
