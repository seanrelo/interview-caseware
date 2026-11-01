package com.caseware.fanout.graph;

import com.caseware.fanout.domain.TemplateId;
import com.caseware.fanout.domain.VersionId;
import java.time.Instant;
import java.util.Comparator;
import java.util.Objects;
import java.util.Set;

/** One node of the version graph (ADR 0004). Edges are the parent links. */
public record TemplateVersion(
    TemplateId templateId,
    VersionId version,
    Set<VersionId> parents,
    MarketBranch branch,
    VersionStatus status,
    Instant publishedAt) {

  /** The single strict total order used for "latest" and "newer": publishedAt, then version value. */
  public static final Comparator<TemplateVersion> ORDER =
      Comparator.comparing(TemplateVersion::publishedAt).thenComparing(v -> v.version().value());

  public TemplateVersion {
    Objects.requireNonNull(templateId, "templateId");
    Objects.requireNonNull(version, "version");
    Objects.requireNonNull(parents, "parents");
    Objects.requireNonNull(branch, "branch");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(publishedAt, "publishedAt");
    parents = Set.copyOf(parents);
  }

  public TemplateVersion withStatus(VersionStatus newStatus) {
    return new TemplateVersion(templateId, version, parents, branch, newStatus, publishedAt);
  }
}
