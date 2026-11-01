package com.caseware.fanout.capture;

import com.caseware.fanout.domain.EfRef;
import com.caseware.fanout.domain.Region;
import com.caseware.fanout.domain.TemplateId;
import com.caseware.fanout.domain.VersionId;
import java.time.Instant;
import java.util.Optional;

/**
 * Regional store of the template-version record, one current record per (region, EF id). All writes
 * are conditional and idempotent (FR-5, NFR-27). There is deliberately no load method: nothing here
 * reads an EF (NFR-1, NFR-22).
 */
public interface TemplateVersionRecordStore {

  /**
   * Writes the version fields. Absent: create (seeds baseline). Newer capturedAt: APPLIED. Equal and
   * identical: NOOP_DUPLICATE. Older: NOOP_STALE. Equal but different: NOOP_CONFLICT. Never touches
   * the baseline except for seeding (see ADR 0002 clarification 5).
   *
   * @throws IllegalArgumentException if source is {@link CaptureSource#ACCEPT}
   */
  UpsertResult upsertVersion(
      Region region,
      EfRef ef,
      TemplateId template,
      VersionId version,
      Instant capturedAt,
      CaptureSource source);

  /**
   * Writes the baseline only (FR-4). No record: NOOP_NO_RECORD. Other template: NOOP_CONFLICT.
   * Otherwise the same conditional rule on baselineCapturedAt.
   */
  UpsertResult upsertBaseline(
      Region region, EfRef ef, TemplateId template, VersionId accepted, Instant decidedAt);

  Optional<TemplateVersionRecord> find(Region region, EfRef ef);
}
