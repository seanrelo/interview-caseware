package com.caseware.fanout.domain;

import java.util.Objects;

/** Idempotency key of a work item (FR-15). */
public record WorkKey(EfId ef, TemplateId templateId, VersionId targetVersion) {
  public WorkKey {
    Objects.requireNonNull(ef, "ef");
    Objects.requireNonNull(templateId, "templateId");
    Objects.requireNonNull(targetVersion, "targetVersion");
  }
}
