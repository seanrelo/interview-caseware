package com.caseware.fanout.port;

import com.caseware.fanout.domain.PublishTotals;

/**
 * Per-publish total (FR-21). Terminal counts are not kept here: they are derived from the work item
 * store ({@link WorkItemStore#countByPublish}) so a crash after a terminal compare-and-set cannot
 * lose a count (NFR-10).
 */
public interface PublishProgressStore {
  /**
   * Atomically sets the total and seals the publish. A no-op if the publish is already sealed, so a
   * redelivery cannot recount (NFR-27).
   */
  void addAndSeal(String publishId, long created);

  PublishTotals totals(String publishId);
}
