package com.caseware.fanout.graph;

import java.util.Objects;

/** Market branch of a template (for example "main", "CA-fr"). */
public record MarketBranch(String value) {
  public MarketBranch {
    Objects.requireNonNull(value, "value");
    if (value.isBlank()) {
      throw new IllegalArgumentException("MarketBranch must not be blank");
    }
  }
}
