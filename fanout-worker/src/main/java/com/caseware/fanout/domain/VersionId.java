package com.caseware.fanout.domain;

import java.util.Objects;

public record VersionId(String value) {
  public VersionId {
    Objects.requireNonNull(value, "value");
    if (value.isBlank()) {
      throw new IllegalArgumentException("VersionId must not be blank");
    }
  }
}
