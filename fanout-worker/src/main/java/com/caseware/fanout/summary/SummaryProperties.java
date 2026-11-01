package com.caseware.fanout.summary;

import java.time.Duration;
import java.util.Objects;

public record SummaryProperties(
    ModelId pinnedModel,
    PromptVersion prompt,
    int maxAttempts,
    Duration leaseTtl,
    Duration baseBackoff,
    Duration maxBackoff,
    double unsupportedMax,
    double omissionMax) {

  public SummaryProperties {
    Objects.requireNonNull(pinnedModel, "pinnedModel");
    Objects.requireNonNull(prompt, "prompt");
    Objects.requireNonNull(leaseTtl, "leaseTtl");
    Objects.requireNonNull(baseBackoff, "baseBackoff");
    Objects.requireNonNull(maxBackoff, "maxBackoff");
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("maxAttempts must be >= 1");
    }
    if (leaseTtl.isZero() || leaseTtl.isNegative()) {
      throw new IllegalArgumentException("leaseTtl must be positive");
    }
    if (baseBackoff.isZero() || baseBackoff.isNegative()) {
      throw new IllegalArgumentException("baseBackoff must be positive");
    }
    if (maxBackoff.compareTo(baseBackoff) < 0) {
      throw new IllegalArgumentException("maxBackoff must be >= baseBackoff");
    }
    if (!(unsupportedMax >= 0 && unsupportedMax <= 1) || !(omissionMax >= 0 && omissionMax <= 1)) {
      throw new IllegalArgumentException("thresholds must be within [0, 1]");
    }
  }

  /** Defaults: 5 attempts, 2 min lease, 1 s to 5 min backoff, 1% unsupported, 5% omission (all to agree). */
  public SummaryProperties(ModelId pinnedModel, PromptVersion prompt) {
    this(pinnedModel, prompt, 5, Duration.ofMinutes(2), Duration.ofSeconds(1), Duration.ofMinutes(5), 0.01, 0.05);
  }
}
