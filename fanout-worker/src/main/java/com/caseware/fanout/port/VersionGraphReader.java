package com.caseware.fanout.port;

import com.caseware.fanout.domain.TemplateId;
import com.caseware.fanout.domain.VersionId;
import java.util.Optional;

/** Read side of the version graph (ADR 0004). Order is graph order, not id comparison. */
public interface VersionGraphReader {
  Optional<VersionId> latestEligible(TemplateId templateId);

  /** True if the version is published and not withdrawn (eligible as a target). Throws for a version the graph does not know. */
  boolean isEligible(TemplateId templateId, VersionId version);

  boolean isNewer(TemplateId templateId, VersionId candidate, VersionId current);
}
