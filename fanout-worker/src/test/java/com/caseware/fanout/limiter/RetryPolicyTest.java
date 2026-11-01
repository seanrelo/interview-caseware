package com.caseware.fanout.limiter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.random.RandomGenerator;
import java.util.SplittableRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {

  private final RetryPolicy policy =
      new RetryPolicy(5, Duration.ofSeconds(1), Duration.ofSeconds(10));

  @Test
  @DisplayName("FR-18: default is 5 attempts and exhaustion starts at the 5th failure")
  void defaultAttempts() {
    RetryPolicy d = RetryPolicy.defaults();
    assertThat(d.maxAttempts()).isEqualTo(5);
    assertThat(d.exhausted(4)).isFalse();
    assertThat(d.exhausted(5)).isTrue();
  }

  @Test
  @DisplayName("FR-18: jitter ceiling doubles per attempt and is capped")
  void ceilingDoublesAndCaps() {
    assertThat(policy.ceiling(1)).isEqualTo(Duration.ofSeconds(1));
    assertThat(policy.ceiling(2)).isEqualTo(Duration.ofSeconds(2));
    assertThat(policy.ceiling(3)).isEqualTo(Duration.ofSeconds(4));
    assertThat(policy.ceiling(4)).isEqualTo(Duration.ofSeconds(8));
    assertThat(policy.ceiling(5)).isEqualTo(Duration.ofSeconds(10));
    assertThat(policy.ceiling(500)).isEqualTo(Duration.ofSeconds(10));
  }

  @Test
  @DisplayName("FR-18: full jitter stays in [0, ceiling] and is reproducible with a seed")
  void fullJitterWithinBounds() {
    RandomGenerator r1 = new SplittableRandom(42);
    RandomGenerator r2 = new SplittableRandom(42);
    boolean sawDifferent = false;
    Duration first = null;
    for (int attempt = 1; attempt <= 5; attempt++) {
      for (int i = 0; i < 200; i++) {
        Duration d = policy.delay(attempt, r1);
        assertThat(d).isBetween(Duration.ZERO, policy.ceiling(attempt));
        if (first == null) {
          first = d;
        } else if (!first.equals(d)) {
          sawDifferent = true;
        }
      }
    }
    assertThat(sawDifferent).isTrue();
    assertThat(policy.delay(3, r2)).isEqualTo(policy.delay(3, new SplittableRandom(42)));
  }

  @Test
  @DisplayName("FR-18: invalid configuration is rejected")
  void validation() {
    assertThatThrownBy(() -> new RetryPolicy(0, Duration.ZERO, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RetryPolicy(5, Duration.ofSeconds(2), Duration.ofSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> policy.ceiling(0)).isInstanceOf(IllegalArgumentException.class);
  }
}
