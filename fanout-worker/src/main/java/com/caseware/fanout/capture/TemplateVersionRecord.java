package com.caseware.fanout.capture;

import com.caseware.fanout.domain.EfRef;
import com.caseware.fanout.domain.Region;
import com.caseware.fanout.domain.TemplateId;
import com.caseware.fanout.domain.VersionId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * The current template-version record of one EF (ADR 0002): no history. Two timestamps, one per
 * conditional write. A seeded baseline carries {@link Instant#EPOCH} so any real accept wins.
 */
public record TemplateVersionRecord(
    Region region,
    EfRef ef,
    TemplateId template,
    VersionId version,
    Optional<VersionId> baseline,
    Instant versionCapturedAt,
    Optional<Instant> baselineCapturedAt,
    CaptureSource source) {

  public TemplateVersionRecord {
    Objects.requireNonNull(region, "region");
    Objects.requireNonNull(ef, "ef");
    Objects.requireNonNull(template, "template");
    Objects.requireNonNull(version, "version");
    Objects.requireNonNull(baseline, "baseline");
    Objects.requireNonNull(versionCapturedAt, "versionCapturedAt");
    Objects.requireNonNull(baselineCapturedAt, "baselineCapturedAt");
    Objects.requireNonNull(source, "source");
    if (baseline.isPresent() != baselineCapturedAt.isPresent()) {
      throw new IllegalArgumentException("baseline and baselineCapturedAt must be set together");
    }
    if (source == CaptureSource.ACCEPT) {
      throw new IllegalArgumentException("ACCEPT is never stored as a record source");
    }
  }
}
