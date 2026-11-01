package com.caseware.fanout.summary;

import java.util.Objects;

public record PromptVersion(String value) {
  public PromptVersion {
    Objects.requireNonNull(value, "value");
    if (value.isBlank()) {
      throw new IllegalArgumentException("prompt version must not be blank");
    }
  }
}
