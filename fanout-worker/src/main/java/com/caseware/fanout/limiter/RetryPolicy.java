package com.caseware.fanout.limiter;

import java.time.Duration;
import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * Retry schedule for a failed downstream call (FR-18, ADR 0007): exponential backoff with full
 * jitter, capped, at most {@code maxAttempts} calls in total (default 5, "to agree").
 *
 * <p>Attempts are 1-based: attempt 1 is the first call. After failed attempt {@code n} the next
 * call is delayed by a uniform random value in {@code [0, min(cap, base * 2^(n-1))]}. Immutable and
 * thread-safe; the caller supplies the random source so tests are deterministic.
 */
public record RetryPolicy(int maxAttempts, Duration baseDelay, Duration maxDelay) {

  public static final int DEFAULT_MAX_ATTEMPTS = 5;

  public RetryPolicy {
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("maxAttempts must be >= 1");
    }
    Objects.requireNonNull(baseDelay, "baseDelay");
    Objects.requireNonNull(maxDelay, "maxDelay");
    if (baseDelay.isNegative() || maxDelay.compareTo(baseDelay) < 0) {
      throw new IllegalArgumentException("need 0 <= baseDelay <= maxDelay");
    }
  }

  public static RetryPolicy defaults() {
    return new RetryPolicy(DEFAULT_MAX_ATTEMPTS, Duration.ofSeconds(1), Duration.ofSeconds(30));
  }

  /** True if attempt number {@code failedAttempt} (1-based) was the last one allowed. */
  public boolean exhausted(int failedAttempt) {
    return failedAttempt >= maxAttempts;
  }

  /** Upper bound of the jitter window after failed attempt {@code attempt} (1-based). */
  public Duration ceiling(int attempt) {
    if (attempt < 1) {
      throw new IllegalArgumentException("attempt must be >= 1");
    }
    long baseMs = baseDelay.toMillis();
    long capMs = maxDelay.toMillis();
    int shift = Math.min(attempt - 1, 40);
    long exp = baseMs > (Long.MAX_VALUE >> shift) ? Long.MAX_VALUE : baseMs << shift;
    return Duration.ofMillis(Math.min(capMs, exp));
  }

  /** Full jitter delay before the next call, after failed attempt {@code attempt} (1-based). */
  public Duration delay(int attempt, RandomGenerator random) {
    long ceilMs = ceiling(attempt).toMillis();
    return Duration.ofMillis(ceilMs == 0 ? 0 : random.nextLong(ceilMs + 1));
  }
}
