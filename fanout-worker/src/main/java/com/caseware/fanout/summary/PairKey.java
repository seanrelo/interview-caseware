package com.caseware.fanout.summary;

import com.caseware.fanout.domain.TemplateId;
import com.caseware.fanout.domain.VersionId;
import java.util.Objects;

/** Firm-independent summary key (FR-36): no firm or EF field by design. */
public record PairKey(TemplateId template, VersionId baseline, VersionId target) {
  public PairKey {
    Objects.requireNonNull(template, "template");
    Objects.requireNonNull(baseline, "baseline");
    Objects.requireNonNull(target, "target");
    if (baseline.equals(target)) {
      throw new IllegalArgumentException("baseline and target must differ");
    }
  }
}
