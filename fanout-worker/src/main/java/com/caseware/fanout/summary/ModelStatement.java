package com.caseware.fanout.summary;

import java.util.List;
import java.util.Objects;

/** Raw, unvalidated model output. Never stored; see {@link SummaryValidator}. */
public record ModelStatement(String text, List<String> diffItemIds) {
  public ModelStatement {
    Objects.requireNonNull(text, "text");
    diffItemIds = List.copyOf(Objects.requireNonNull(diffItemIds, "diffItemIds"));
  }
}
