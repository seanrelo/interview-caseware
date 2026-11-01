package com.caseware.fanout.config;

import com.caseware.fanout.domain.Region;
import com.caseware.fanout.limiter.LimiterProperties;
import com.caseware.fanout.limiter.RetryPolicy;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Every "to agree" value of spec 03 in one place. Defaults are placeholders until the owning team
 * answers Q1; none of them is a measured or agreed number except where the spec states it.
 */
@ConfigurationProperties(prefix = "fanout")
public record FanoutProperties(
    @DefaultValue("EU") Region region,
    @DefaultValue("500") int pageSize,
    @DefaultValue("100") int batchSize,
    @DefaultValue("PT2M") Duration leaseDuration,
    @DefaultValue Retry retry,
    @DefaultValue Limiter limiter,
    @DefaultValue Dlq dlq) {

  public FanoutProperties {
    if (batchSize <= 0 || pageSize <= 0) {
      throw new IllegalArgumentException("batchSize and pageSize must be > 0");
    }
  }

  /** FR-18: 5 attempts (to agree), exponential, full jitter. */
  public record Retry(
      @DefaultValue("5") int maxAttempts,
      @DefaultValue("PT1S") Duration baseDelay,
      @DefaultValue("PT30S") Duration maxDelay) {
    public RetryPolicy toPolicy() {
      return new RetryPolicy(maxAttempts, baseDelay, maxDelay);
    }
  }

  /** NFR-8, NFR-9, NFR-11. maxInFlight is the limit "to agree" with the owning team. */
  public record Limiter(
      @DefaultValue("50") int maxInFlight,
      @DefaultValue("1") int minInFlight,
      @DefaultValue("0.10") double errorRateThreshold,
      @DefaultValue("PT1M") Duration errorWindow,
      @DefaultValue("20") int errorMinSamples,
      @DefaultValue("PT1S") Duration decreaseCooldown,
      @DefaultValue("0.25") double firmShare) {
    public LimiterProperties toProperties() {
      return new LimiterProperties(
          maxInFlight,
          minInFlight,
          errorRateThreshold,
          errorWindow,
          errorMinSamples,
          decreaseCooldown,
          firmShare);
    }
  }

  /** NFR-29: retention 14 days (to agree). */
  public record Dlq(@DefaultValue("P14D") Duration retention) {}
}
