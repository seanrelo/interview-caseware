package com.caseware.fanout.graph;

import com.caseware.fanout.domain.TemplateId;
import com.caseware.fanout.domain.VersionId;
import java.util.Collection;
import java.util.Optional;

/** Immutable, pure view of the version graph. Never loads an EF (NFR-22). */
public interface VersionGraph {
  Optional<TemplateVersion> find(TemplateId templateId, VersionId version);

  /** All versions of a template, in total order (oldest first). */
  Collection<TemplateVersion> versions(TemplateId templateId);

  /** Eligible target for a baseline within the baseline's own branch. Unknown baseline throws IAE. */
  EligibleResult eligibleVersion(TemplateId templateId, VersionId baseline);

  EligibleResult eligibleVersion(TemplateId templateId, VersionId baseline, MarketBranch branch);

  /** Total order comparison. Unknown version throws IAE. */
  boolean isNewer(TemplateId templateId, VersionId candidate, VersionId current);
}
