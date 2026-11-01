package com.caseware.fanout.status;

import com.caseware.fanout.domain.FirmId;
import java.util.Objects;

/** Authenticated identity; the firm is never taken from a client parameter (NFR-33). */
public record Caller(FirmId firm, String user) {
  public Caller {
    Objects.requireNonNull(firm, "firm");
    Objects.requireNonNull(user, "user");
    if (user.isBlank()) {
      throw new IllegalArgumentException("user must not be blank");
    }
  }
}
