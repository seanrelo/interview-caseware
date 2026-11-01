package com.caseware.fanout.graph;

import com.caseware.fanout.domain.EfRef;
import com.caseware.fanout.domain.Page;
import com.caseware.fanout.domain.Region;
import com.caseware.fanout.domain.TemplateId;
import com.caseware.fanout.domain.VersionId;
import java.util.Optional;

/**
 * Regional reverse index (region, template, pending target) to EFs (FR-13, ADR 0004/0008). It is
 * the only way the re-evaluator finds affected EFs, so no EF is ever loaded (NFR-22). A region's
 * entries are never visible from another region (NFR-35).
 */
public interface PendingTargetIndex {
  /** Idempotent. */
  void record(Region region, TemplateId templateId, VersionId pendingTarget, EfRef ef);

  /** Removes the entry only for this exact pending target. Idempotent. */
  void remove(Region region, TemplateId templateId, VersionId pendingTarget, EfRef ef);

  /** EFs whose pending target is exactly {@code withdrawn}. Stable order, token paging. */
  Page<EfRef> affectedBy(
      Region region,
      TemplateId templateId,
      VersionId withdrawn,
      Optional<String> pageToken,
      int pageSize);
}
