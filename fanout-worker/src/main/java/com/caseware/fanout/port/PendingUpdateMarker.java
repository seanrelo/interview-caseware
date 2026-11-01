package com.caseware.fanout.port;

import com.caseware.fanout.domain.WorkKey;
import com.caseware.fanout.graph.EligibleResult;

/**
 * The FR-16 downstream call: a write to the regional status store. There is deliberately no EF-load
 * method (NFR-22). Both writes are version-monotonic (ADR 0006 clarification 2 and 3).
 */
public interface PendingUpdateMarker {
  /** Sets the pending target if newer than the stored one. */
  MarkResult record(WorkKey key) throws DownstreamThrottledException, DownstreamException;

  /**
   * Sets status unknown (FR-19) unless a newer pending target is stored. The reason is internal
   * only, never user-visible.
   */
  void markUnknown(WorkKey key, String internalReason);

  /**
   * Withdrawal re-evaluation (FR-13, ADR 0006): compare-and-set on the withdrawn target. Applies
   * only if the stored pending target for (EF, template) is exactly {@code withdrawnTarget};
   * otherwise returns UNCHANGED and writes nothing. Eligible(v) sets pending v even if v is older
   * than the withdrawn target (the one exception to version monotonicity: a withdrawn target is
   * ineligible); UpToDate clears pending (status becomes up to date); Unknown sets unknown. Never
   * overwrites a newer pending target. Idempotent.
   */
  MarkResult reevaluate(WorkKey withdrawnTarget, EligibleResult result);
}
