package com.caseware.fanout.graph;

import com.caseware.fanout.domain.TemplateId;
import com.caseware.fanout.domain.VersionId;
import java.util.Optional;

/** Write side of the version graph. */
public interface VersionRegistry {
  /**
   * Idempotent for an identical record. A conflicting re-register, an unknown parent, a cycle or a
   * child not strictly later than every parent is rejected with IllegalArgumentException.
   */
  void register(TemplateVersion version);

  void registerContent(VersionContent content);

  /** Builds the publish event for a registered, PUBLISHED version. Unknown throws IAE. */
  GraphPublishEvent publish(TemplateId templateId, VersionId version);

  /** Flag only: keeps node, edges, content and diffs. Idempotent. Unknown throws IAE. */
  void withdraw(TemplateId templateId, VersionId version);

  VersionGraph snapshot();

  Optional<VersionContent> content(TemplateId templateId, VersionId version);
}
