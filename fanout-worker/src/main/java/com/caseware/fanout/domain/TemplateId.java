package com.caseware.fanout.domain;

import java.util.Objects;

public record TemplateId(String value) {
  public TemplateId {
    Objects.requireNonNull(value, "value");
    if (value.isBlank()) {
      throw new IllegalArgumentException("TemplateId must not be blank");
    }
  }
}
