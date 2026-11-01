package com.caseware.fanout.limiter;

import java.time.Duration;

/**
 * Limits for the adaptive limiter. Every value except {@code minInFlight} is "to agree" with the
 * owning team (NFR-8, NFR-9, NFR-11, ADR 0007). Plain record: bind it from Spring properties in the
 * wiring layer, the limiter itself has no framework dependency.
 *
 * @param maxInFlight configured maximum and starting limit of in-flight downstream calls (NFR-8)
 * @param minInFlight floor of the limit; never below 1 so a backlog always drains
 * @param errorRateThreshold error rate over {@code errorWindow} above which the limit is halved (0.10, NFR-9)
 * @param errorWindow sliding window for the error rate (1 minute, NFR-9)
 * @param errorMinSamples outcomes required in the window before the rate is trusted
 * @param decreaseCooldown after a decrease further decreases are ignored for this long, so one burst
 *     of throttled responses from calls already in flight halves the limit once, not to 1
 * @param firmShare maximum share of the current limit one firm may hold while other firms have
 *     queued work (0.25, NFR-11)
 */
public record LimiterProperties(
    int maxInFlight,
    int minInFlight,
    double errorRateThreshold,
    Duration errorWindow,
    int errorMinSamples,
    Duration decreaseCooldown,
    double firmShare) {

  public LimiterProperties {
    if (minInFlight < 1 || maxInFlight < minInFlight) {
      throw new IllegalArgumentException("need 1 <= minInFlight <= maxInFlight");
    }
    if (errorRateThreshold <= 0 || errorRateThreshold >= 1) {
      throw new IllegalArgumentException("errorRateThreshold must be in (0,1)");
    }
    if (errorWindow.isZero() || errorWindow.isNegative() || decreaseCooldown.isNegative()) {
      throw new IllegalArgumentException("errorWindow must be positive, cooldown not negative");
    }
    if (errorMinSamples < 1) {
      throw new IllegalArgumentException("errorMinSamples must be >= 1");
    }
    if (firmShare <= 0 || firmShare > 1) {
      throw new IllegalArgumentException("firmShare must be in (0,1]");
    }
  }

  /** The values proposed in ADR 0007; {@code maxInFlight} has no default because it is agreed downstream. */
  public static LimiterProperties defaults(int maxInFlight) {
    return new LimiterProperties(
        maxInFlight, 1, 0.10, Duration.ofMinutes(1), 20, Duration.ofSeconds(1), 0.25);
  }
}
