package com.caseware.fanout.app;

import com.caseware.fanout.port.DeadLetterSink;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Registers gauge {@code dlq.depth} (NFR-38, NFR-39). Alert rule, to agree: depth above 0 for 15
 * minutes pages the owning team (configured in CloudWatch, not in this code). Entries are kept for
 * the DLQ retention (14 days, NFR-29).
 */
public final class DeadLetterMetrics {
  public static final String DLQ_DEPTH = "dlq.depth";

  private DeadLetterMetrics() {}

  public static void bind(MeterRegistry registry, DeadLetterSink sink) {
    Gauge.builder(DLQ_DEPTH, sink, DeadLetterSink::depth)
        .description("Dead-lettered work items awaiting action")
        .register(registry);
  }
}
