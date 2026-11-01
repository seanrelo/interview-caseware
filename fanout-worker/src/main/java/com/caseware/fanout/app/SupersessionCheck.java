package com.caseware.fanout.app;

import com.caseware.fanout.domain.WorkItem;

/** Seam for FR-20 (T6): evaluated at execution time, before the downstream call. */
@FunctionalInterface
public interface SupersessionCheck {
  SupersessionCheck NEVER = item -> false;

  boolean superseded(WorkItem item);
}
