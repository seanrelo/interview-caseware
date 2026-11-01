package com.caseware.fanout.limiter;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.function.Supplier;

/** Micrometer gauges for NFR-38. dlq.depth and publish latency are registered elsewhere. */
public final class LimiterMetrics {

  public static final String BACKLOG = "fanout.backlog";
  public static final String IN_FLIGHT = "fanout.inflight";
  public static final String LIMIT = "fanout.limit";

  private LimiterMetrics() {}

  /** {@code backlog} supplies the number of queued items (the store port has no count yet). */
  public static void register(
      MeterRegistry registry, AdaptiveLimiter limiter, Supplier<Number> backlog) {
    Gauge.builder(BACKLOG, backlog).description("Work items queued").register(registry);
    Gauge.builder(IN_FLIGHT, limiter, AdaptiveLimiter::inFlight)
        .description("In-flight downstream calls")
        .register(registry);
    Gauge.builder(LIMIT, limiter, AdaptiveLimiter::limit)
        .description("Current in-flight limit")
        .register(registry);
  }
}
