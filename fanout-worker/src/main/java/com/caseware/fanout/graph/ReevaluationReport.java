package com.caseware.fanout.graph;

import com.caseware.fanout.domain.EfRef;
import java.util.List;

/**
 * Outcome counts of one withdrawal re-evaluation. A failed EF is listed in {@code failed} and is
 * never counted as upToDate.
 */
public record ReevaluationReport(
    int evaluated,
    int markedPending,
    int upToDate,
    int markedUnknown,
    int skippedFlagOff,
    List<Failure> failed) {

  public record Failure(EfRef ef, String reason) {}

  public ReevaluationReport {
    failed = List.copyOf(failed);
  }
}
