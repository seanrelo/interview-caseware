package com.caseware.fanout.summary;

import java.time.Duration;

public interface SummaryMetrics {
  void generated(PairKey pair);

  void failed(FailureKind kind);

  /** Time from publish (job creation) to a stored summary (NFR-20). */
  void readyLatency(Duration latency);
}
