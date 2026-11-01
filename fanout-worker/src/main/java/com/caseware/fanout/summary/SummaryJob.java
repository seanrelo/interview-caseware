package com.caseware.fanout.summary;

import java.util.Objects;

/** A leased unit of work. attempt starts at 1 and grows on each retryLater. */
public record SummaryJob(PairKey pair, JobReason reason, int attempt) {
  public SummaryJob {
    Objects.requireNonNull(pair, "pair");
    Objects.requireNonNull(reason, "reason");
    if (attempt < 1) {
      throw new IllegalArgumentException("attempt must be >= 1");
    }
  }
}
