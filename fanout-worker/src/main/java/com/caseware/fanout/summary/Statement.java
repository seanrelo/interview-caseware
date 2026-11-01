package com.caseware.fanout.summary;

import java.util.List;
import java.util.Objects;

/** A validated statement: text plus at least one diff item id (FR-39). */
public record Statement(String text, List<String> diffItemIds) {
  public Statement {
    Objects.requireNonNull(text, "text");
    diffItemIds = List.copyOf(Objects.requireNonNull(diffItemIds, "diffItemIds"));
    if (diffItemIds.isEmpty()) {
      throw new IllegalArgumentException("a statement needs at least one diff item id");
    }
  }
}
