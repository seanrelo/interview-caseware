package com.caseware.fanout.summary;

import java.time.Instant;
import java.util.Objects;

public record SummaryDeadLetter(SummaryJob job, String cause, Instant at) {
  public SummaryDeadLetter {
    Objects.requireNonNull(job, "job");
    Objects.requireNonNull(cause, "cause");
    Objects.requireNonNull(at, "at");
  }
}
