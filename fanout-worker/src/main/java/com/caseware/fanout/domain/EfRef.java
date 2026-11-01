package com.caseware.fanout.domain;

import java.util.Objects;

public record EfRef(EfId ef, FirmId firm) {
  public EfRef {
    Objects.requireNonNull(ef, "ef");
    Objects.requireNonNull(firm, "firm");
  }
}
