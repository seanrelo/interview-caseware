package com.caseware.fanout.domain;

import java.util.Objects;

public record FirmId(String value) {
  public FirmId {
    Objects.requireNonNull(value, "value");
    if (value.isBlank()) {
      throw new IllegalArgumentException("FirmId must not be blank");
    }
  }
}
