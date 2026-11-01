package com.caseware.fanout.status;

import com.caseware.fanout.domain.EfRef;
import com.caseware.fanout.domain.TemplateId;
import com.caseware.fanout.domain.VersionId;
import java.time.Instant;
import java.util.Objects;

/** One immutable decision record pinned to (EF, version), ADR 0009 (FR-27, FR-31). */
public record Decision(
    EfRef ef, TemplateId template, VersionId version, DecisionType type, String user, Instant at) {
  public Decision {
    Objects.requireNonNull(ef, "ef");
    Objects.requireNonNull(template, "template");
    Objects.requireNonNull(version, "version");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(user, "user");
    Objects.requireNonNull(at, "at");
    if (user.isBlank()) {
      throw new IllegalArgumentException("user must not be blank");
    }
  }
}
