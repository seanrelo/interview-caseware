package com.caseware.fanout.app;

import com.caseware.fanout.domain.VersionId;
import com.caseware.fanout.domain.WorkItem;
import com.caseware.fanout.port.VersionGraphReader;
import java.util.Objects;
import java.util.Optional;

/**
 * FR-20 (ADR 0006): at execution time an item is superseded when its target is not the latest
 * eligible version of its template and either (a) the target is itself withdrawn or ineligible, or
 * (b) the latest eligible version is newer, in graph order, than the target. Evaluated per call,
 * never cached across the lease, so "unprocessed" is decided at the moment of execution.
 *
 * <p>An item that targets the latest eligible version is processed. If the graph cannot answer
 * (unknown version, read failure) the check fails open and the item is processed: the marker write
 * is version-monotonic (NFR-12), so processing is safe.
 */
public class VersionSupersessionCheck implements SupersessionCheck {

  private final VersionGraphReader graph;

  public VersionSupersessionCheck(VersionGraphReader graph) {
    this.graph = Objects.requireNonNull(graph);
  }

  @Override
  public boolean superseded(WorkItem item) {
    VersionId target = item.key().targetVersion();
    try {
      if (!graph.isEligible(item.key().templateId(), target)) {
        return true;
      }
      Optional<VersionId> latest = graph.latestEligible(item.key().templateId());
      return latest.isPresent()
          && !latest.get().equals(target)
          && graph.isNewer(item.key().templateId(), latest.get(), target);
    } catch (RuntimeException e) {
      return false;
    }
  }
}
