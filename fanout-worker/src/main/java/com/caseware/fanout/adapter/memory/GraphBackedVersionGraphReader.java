package com.caseware.fanout.adapter.memory;

import com.caseware.fanout.domain.TemplateId;
import com.caseware.fanout.domain.VersionId;
import com.caseware.fanout.graph.TemplateVersion;
import com.caseware.fanout.graph.VersionGraph;
import com.caseware.fanout.graph.VersionRegistry;
import com.caseware.fanout.graph.VersionStatus;
import com.caseware.fanout.port.VersionGraphReader;
import java.util.Optional;

/** Adapts the version graph to the worker's read port. Same total order as the stub. */
public class GraphBackedVersionGraphReader implements VersionGraphReader {

  private final VersionRegistry registry;

  public GraphBackedVersionGraphReader(VersionRegistry registry) {
    this.registry = registry;
  }

  @Override
  public Optional<VersionId> latestEligible(TemplateId templateId) {
    return registry.snapshot().versions(templateId).stream()
        .filter(v -> v.status() == VersionStatus.PUBLISHED)
        .max(TemplateVersion.ORDER)
        .map(TemplateVersion::version);
  }

  @Override
  public boolean isEligible(TemplateId templateId, VersionId version) {
    return registry
            .snapshot()
            .find(templateId, version)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "unknown version " + version + " of " + templateId))
            .status()
        == VersionStatus.PUBLISHED;
  }

  @Override
  public boolean isNewer(TemplateId templateId, VersionId candidate, VersionId current) {
    return registry.snapshot().isNewer(templateId, candidate, current);
  }
}
