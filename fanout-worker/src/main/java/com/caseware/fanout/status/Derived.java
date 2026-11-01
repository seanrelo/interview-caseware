package com.caseware.fanout.status;

import com.caseware.fanout.domain.VersionId;
import java.util.Objects;
import java.util.Optional;

/** Result of {@link StatusDeriver#derive}: target is present iff UPDATE_PENDING. */
public record Derived(UpdateStatus status, Optional<VersionId> baseline, Optional<VersionId> target) {
  public Derived {
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(baseline, "baseline");
    Objects.requireNonNull(target, "target");
    if (target.isPresent() != (status == UpdateStatus.UPDATE_PENDING)) {
      throw new IllegalArgumentException("target is present if and only if UPDATE_PENDING");
    }
  }

  static Derived unknown(Optional<VersionId> baseline) {
    return new Derived(UpdateStatus.UNKNOWN, baseline, Optional.empty());
  }
}
