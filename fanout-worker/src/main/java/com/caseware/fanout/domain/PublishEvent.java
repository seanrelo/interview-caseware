package com.caseware.fanout.domain;

import java.time.Instant;
import java.util.Objects;

/** Global publish event. Carries template and version only, never an EF (NFR-36). */
public record PublishEvent(
    String publishId, TemplateId templateId, VersionId version, Instant publishedAt) {
  public PublishEvent {
    Objects.requireNonNull(publishId, "publishId");
    Objects.requireNonNull(templateId, "templateId");
    Objects.requireNonNull(version, "version");
    Objects.requireNonNull(publishedAt, "publishedAt");
  }
}
