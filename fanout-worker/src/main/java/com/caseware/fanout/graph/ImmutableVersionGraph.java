package com.caseware.fanout.graph;

import com.caseware.fanout.domain.TemplateId;
import com.caseware.fanout.domain.VersionId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Immutable snapshot. Pure and deterministic: no clock, no randomness, no I/O. Candidates are the
 * PUBLISHED strict descendants of the baseline in the requested branch; withdrawn nodes are
 * traversable but never selected.
 */
public final class ImmutableVersionGraph implements VersionGraph {

  private final Map<TemplateId, Map<VersionId, TemplateVersion>> nodes;
  private final Map<TemplateId, Map<VersionId, List<VersionId>>> children;

  public ImmutableVersionGraph(Collection<TemplateVersion> all) {
    Map<TemplateId, Map<VersionId, TemplateVersion>> n = new TreeMap<>(byTemplate());
    for (TemplateVersion v : all) {
      n.computeIfAbsent(v.templateId(), t -> new TreeMap<>(byVersion())).put(v.version(), v);
    }
    Map<TemplateId, Map<VersionId, List<VersionId>>> c = new TreeMap<>(byTemplate());
    n.forEach(
        (t, versions) -> {
          Map<VersionId, List<VersionId>> kids = new TreeMap<>(byVersion());
          for (TemplateVersion v : versions.values()) {
            for (VersionId p : v.parents()) {
              kids.computeIfAbsent(p, k -> new ArrayList<>()).add(v.version());
            }
          }
          c.put(t, kids);
        });
    n.replaceAll((t, m) -> Collections.unmodifiableMap(m));
    this.nodes = Collections.unmodifiableMap(n);
    this.children = Collections.unmodifiableMap(c);
  }

  private static java.util.Comparator<TemplateId> byTemplate() {
    return java.util.Comparator.comparing(TemplateId::value);
  }

  private static java.util.Comparator<VersionId> byVersion() {
    return java.util.Comparator.comparing(VersionId::value);
  }

  @Override
  public Optional<TemplateVersion> find(TemplateId templateId, VersionId version) {
    return Optional.ofNullable(nodes.getOrDefault(templateId, Map.of()).get(version));
  }

  @Override
  public Collection<TemplateVersion> versions(TemplateId templateId) {
    return nodes.getOrDefault(templateId, Map.of()).values().stream()
        .sorted(TemplateVersion.ORDER)
        .toList();
  }

  private TemplateVersion require(TemplateId templateId, VersionId version) {
    return find(templateId, version)
        .orElseThrow(
            () -> new IllegalArgumentException("unknown version " + version + " of " + templateId));
  }

  @Override
  public EligibleResult eligibleVersion(TemplateId templateId, VersionId baseline) {
    return eligibleVersion(templateId, baseline, require(templateId, baseline).branch());
  }

  @Override
  public EligibleResult eligibleVersion(
      TemplateId templateId, VersionId baseline, MarketBranch branch) {
    TemplateVersion base = require(templateId, baseline);
    Map<VersionId, TemplateVersion> all = nodes.get(templateId);
    Map<VersionId, List<VersionId>> kids = children.get(templateId);

    Set<VersionId> reachable = new HashSet<>();
    Deque<VersionId> queue = new ArrayDeque<>();
    queue.add(baseline);
    TemplateVersion best = null;
    while (!queue.isEmpty()) {
      for (VersionId k : kids.getOrDefault(queue.poll(), List.of())) {
        if (!reachable.add(k)) {
          continue;
        }
        queue.add(k);
        TemplateVersion kv = all.get(k);
        if (kv.status() == VersionStatus.PUBLISHED
            && kv.branch().equals(branch)
            && (best == null || TemplateVersion.ORDER.compare(kv, best) > 0)) {
          best = kv;
        }
      }
    }
    if (best != null) {
      return new EligibleResult.Eligible(best.version());
    }
    for (TemplateVersion v : all.values()) {
      if (v.branch().equals(branch)
          && !reachable.contains(v.version())
          && TemplateVersion.ORDER.compare(v, base) > 0) {
        return new EligibleResult.Unknown(
            "version "
                + v.version().value()
                + " on branch "
                + branch.value()
                + " is newer than baseline "
                + baseline.value()
                + " but not reachable from it");
      }
    }
    return new EligibleResult.UpToDate();
  }

  @Override
  public boolean isNewer(TemplateId templateId, VersionId candidate, VersionId current) {
    return TemplateVersion.ORDER.compare(
            require(templateId, candidate), require(templateId, current))
        > 0;
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof ImmutableVersionGraph g && nodes.equals(g.nodes);
  }

  @Override
  public int hashCode() {
    return nodes.hashCode();
  }
}
