package com.caseware.fanout.capture;

import com.caseware.fanout.domain.TemplateId;
import com.caseware.fanout.domain.VersionId;
import java.util.Objects;

/** The template ID and version read from a loaded EF. */
public record TemplateVersionRef(TemplateId template, VersionId version) {
  public TemplateVersionRef {
    Objects.requireNonNull(template, "template");
    Objects.requireNonNull(version, "version");
  }
}
