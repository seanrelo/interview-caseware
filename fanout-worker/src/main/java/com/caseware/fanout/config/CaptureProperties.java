package com.caseware.fanout.config;

import com.caseware.fanout.capture.BackfillJob;
import com.caseware.fanout.capture.CaptureRelay;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Spec 01 capture settings. All defaults are placeholders ("to agree"). Off by default: with
 * {@code fanout.capture.enabled=false} none of the capture beans exist (NFR-40, NFR-42).
 */
@ConfigurationProperties(prefix = "fanout.capture")
public record CaptureProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("5") int maxAttempts,
    @DefaultValue("100000") int outboxCapacity,
    @DefaultValue("10000") int fallbackCapacity,
    @DefaultValue Relay relay,
    @DefaultValue Backfill backfill) {

  /** Backfill (ADR 0003). Concurrency 50 is planned, 40 is the minimum for 14 days (to agree). */
  public record Backfill(
      @DefaultValue("50") int concurrency,
      @DefaultValue("500") int pageSize,
      @DefaultValue("PT30S") Duration retryBase,
      @DefaultValue("PT1H") Duration retryMax) {
    public BackfillJob.Config toConfig() {
      return new BackfillJob.Config(pageSize, retryBase, retryMax);
    }
  }


  public record Relay(
      @DefaultValue("100") int batchSize,
      @DefaultValue("PT30S") Duration lease,
      @DefaultValue("PT2S") Duration storeTimeout,
      @DefaultValue("5") int breakerFailures,
      @DefaultValue("PT30S") Duration breakerOpenFor,
      @DefaultValue("PT1S") Duration backoffBase,
      @DefaultValue("PT1M") Duration backoffMax) {
    public CaptureRelay.Config toConfig() {
      return new CaptureRelay.Config(
          batchSize, lease, storeTimeout, breakerFailures, breakerOpenFor, backoffBase, backoffMax);
    }
  }
}
