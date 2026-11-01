package com.caseware.fanout.summary;

import com.caseware.fanout.domain.TemplateId;
import com.caseware.fanout.domain.VersionId;
import java.util.Set;

/** Bare version ids only, exported by regional cells without EF or firm identifiers (FR-35, NFR-36). */
public interface BaselinesInUse {
  Set<VersionId> baselines(TemplateId template);
}
