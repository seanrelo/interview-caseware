package com.caseware.fanout.summary;

import java.time.Instant;
import java.util.Objects;

public record Lease(PairKey pair, String owner, Instant expiresAt) {
  public Lease {
    Objects.requireNonNull(pair, "pair");
    Objects.requireNonNull(owner, "owner");
    Objects.requireNonNull(expiresAt, "expiresAt");
  }
}
