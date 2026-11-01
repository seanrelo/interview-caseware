package com.caseware.fanout.app;

import com.caseware.fanout.domain.ItemState;
import com.caseware.fanout.domain.WorkItem;

/** Metrics seam: called after a successful terminal CAS on the work item. Holds no state. */
@FunctionalInterface
public interface TerminalListener {
  TerminalListener NONE = (item, terminal) -> {};

  void onTerminal(WorkItem item, ItemState terminal);
}
