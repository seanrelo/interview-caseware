package com.caseware.fanout.adapter.memory;

import com.caseware.fanout.domain.TemplateId;
import com.caseware.fanout.domain.VersionId;
import com.caseware.fanout.port.VersionGraphReader;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stub of the version graph. Order is a total order: publish time desc, then version id desc (Q3
 * tie-break, proposed). The real graph (ADR 0004) replaces this.
 */
public class InMemoryVersionGraphReader implements VersionGraphReader {

  private record Node(VersionId version, Instant publishedAt, boolean eligible) {}

  private static final Comparator<Node> OLDER_TO_NEWER =
      Comparator.comparing(Node::publishedAt).thenComparing(n -> n.version().value());

  private final Map<TemplateId, Map<VersionId, Node>> graph = new ConcurrentHashMap<>();

  public void addVersion(
      TemplateId templateId, VersionId version, Instant publishedAt, boolean eligible) {
    graph
        .computeIfAbsent(templateId, t -> new ConcurrentHashMap<>())
        .put(version, new Node(version, publishedAt, eligible));
  }

  public void withdraw(TemplateId templateId, VersionId version) {
    Map<VersionId, Node> versions = graph.get(templateId);
    if (versions != null) {
      versions.computeIfPresent(version, (v, n) -> new Node(v, n.publishedAt(), false));
    }
  }

  @Override
  public Optional<VersionId> latestEligible(TemplateId templateId) {
    return graph.getOrDefault(templateId, Map.of()).values().stream()
        .filter(Node::eligible)
        .max(OLDER_TO_NEWER)
        .map(Node::version);
  }

  @Override
  public boolean isEligible(TemplateId templateId, VersionId version) {
    return node(templateId, version).eligible(); // unknown version throws, like isNewer
  }

  @Override
  public boolean isNewer(TemplateId templateId, VersionId candidate, VersionId current) {
    return OLDER_TO_NEWER.compare(node(templateId, candidate), node(templateId, current)) > 0;
  }

  private Node node(TemplateId templateId, VersionId version) {
    Node n = graph.getOrDefault(templateId, Map.of()).get(version);
    if (n == null) {
      throw new IllegalArgumentException("unknown version " + version + " of " + templateId);
    }
    return n;
  }
}
