package com.caseware.fanout.adapter.memory;

import com.caseware.fanout.domain.TemplateId;
import com.caseware.fanout.domain.VersionId;
import com.caseware.fanout.graph.GraphPublishEvent;
import com.caseware.fanout.graph.ImmutableVersionGraph;
import com.caseware.fanout.graph.TemplateVersion;
import com.caseware.fanout.graph.VersionContent;
import com.caseware.fanout.graph.VersionGraph;
import com.caseware.fanout.graph.VersionRegistry;
import com.caseware.fanout.graph.VersionStatus;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Thread-safe registry: all state changes are serialised, snapshots are immutable copies. */
public class InMemoryVersionRegistry implements VersionRegistry {

  private record Key(TemplateId templateId, VersionId version) {}

  private final Map<Key, TemplateVersion> versions = new HashMap<>();
  private final Map<Key, VersionContent> contents = new HashMap<>();

  @Override
  public synchronized void register(TemplateVersion v) {
    Key key = new Key(v.templateId(), v.version());
    TemplateVersion existing = versions.get(key);
    if (existing != null) {
      if (existing.equals(v)) {
        return;
      }
      throw new IllegalArgumentException("conflicting re-register of " + v.version());
    }
    for (VersionId p : v.parents()) {
      TemplateVersion parent = versions.get(new Key(v.templateId(), p));
      if (parent == null) {
        throw new IllegalArgumentException("unknown parent " + p + " of " + v.version());
      }
      if (!v.publishedAt().isAfter(parent.publishedAt())) {
        throw new IllegalArgumentException(
            v.version() + " must be published strictly after parent " + p);
      }
    }
    // Parents must already exist and be strictly earlier, so a cycle cannot be formed.
    versions.put(key, v);
  }

  @Override
  public synchronized void registerContent(VersionContent c) {
    Key key = new Key(c.templateId(), c.version());
    if (!versions.containsKey(key)) {
      throw new IllegalArgumentException("unknown version " + c.version());
    }
    VersionContent existing = contents.putIfAbsent(key, c);
    if (existing != null && !existing.equals(c)) {
      throw new IllegalArgumentException("content is immutable for " + c.version());
    }
  }

  @Override
  public synchronized GraphPublishEvent publish(TemplateId templateId, VersionId version) {
    TemplateVersion v = require(templateId, version);
    if (v.status() != VersionStatus.PUBLISHED) {
      throw new IllegalStateException("version " + version + " is withdrawn");
    }
    return new GraphPublishEvent(templateId, version);
  }

  @Override
  public synchronized void withdraw(TemplateId templateId, VersionId version) {
    TemplateVersion v = require(templateId, version);
    versions.put(new Key(templateId, version), v.withStatus(VersionStatus.WITHDRAWN));
  }

  @Override
  public synchronized VersionGraph snapshot() {
    return new ImmutableVersionGraph(versions.values());
  }

  @Override
  public synchronized Optional<VersionContent> content(TemplateId templateId, VersionId version) {
    return Optional.ofNullable(contents.get(new Key(templateId, version)));
  }

  private TemplateVersion require(TemplateId templateId, VersionId version) {
    TemplateVersion v = versions.get(new Key(templateId, version));
    if (v == null) {
      throw new IllegalArgumentException("unknown version " + version + " of " + templateId);
    }
    return v;
  }
}
