package com.caseware.fanout.summary;

import java.util.Objects;

/** What a caller sees for one pending EF (FR-42, FR-43). */
public sealed interface SummaryView {
  String LABEL = "AI-generated";
  String UNAVAILABLE = "summary unavailable";

  record Ready(SummaryRecord summary) implements SummaryView {
    public Ready {
      Objects.requireNonNull(summary, "summary");
    }

    public String label() {
      return LABEL;
    }
  }

  record Generating() implements SummaryView {}

  record Unavailable() implements SummaryView {
    public String message() {
      return UNAVAILABLE;
    }
  }
}
