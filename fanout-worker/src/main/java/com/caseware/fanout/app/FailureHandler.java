package com.caseware.fanout.app;

import com.caseware.fanout.domain.WorkItem;
import com.caseware.fanout.port.DownstreamException;

/**
 * Seam for FR-18/FR-19 (T4): decides retry (reschedule) or dead-letter after a failed call. It
 * receives the leased item and must resolve it through the store (CAS on the lease token). If it
 * does nothing or throws, the item stays LEASED and becomes re-leasable when the lease expires.
 */
@FunctionalInterface
public interface FailureHandler {
  /** Default: no handler; the item is neither DONE nor counted as up to date. */
  FailureHandler NONE = (item, failure) -> {};

  void onFailure(WorkItem leased, DownstreamException failure);
}
