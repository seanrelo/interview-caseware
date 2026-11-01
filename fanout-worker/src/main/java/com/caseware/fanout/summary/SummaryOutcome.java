package com.caseware.fanout.summary;

import java.util.Objects;

/** Result of asking for a pair's summary. Failed: a dead-lettered pair, or a failed explicit supersede. */
public sealed interface SummaryOutcome {
  record Ready(SummaryRecord summary) implements SummaryOutcome {
    public Ready {
      Objects.requireNonNull(summary, "summary");
    }
  }

  /** A job is outstanding (or another worker holds the lease); nothing is stored yet. */
  record Generating() implements SummaryOutcome {}

  /** Generation failed for good (dead-lettered) or synchronously; nothing was stored (FR-42). */
  record Failed(FailureKind kind, String message) implements SummaryOutcome {}
}
