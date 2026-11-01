package com.caseware.fanout.domain;

import java.util.Objects;

public record EfId(String value) {
  public EfId {
    Objects.requireNonNull(value, "value");
    if (value.isBlank()) {
      throw new IllegalArgumentException("EfId must not be blank");
    }
  }
}
