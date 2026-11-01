package com.caseware.fanout.summary;

import java.util.List;
import java.util.Objects;

public record ModelOutput(List<ModelStatement> statements) {
  public ModelOutput {
    statements = List.copyOf(Objects.requireNonNull(statements, "statements"));
  }
}
