package com.caseware.fanout.summary;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Objects;

/** Optional Micrometer bridge; the rest of the package does not depend on it. */
public class MicrometerSummaryMetrics implements SummaryMetrics {
  private final MeterRegistry registry;

  public MicrometerSummaryMetrics(MeterRegistry registry) {
    this.registry = Objects.requireNonNull(registry, "registry");
  }

  @Override
  public void generated(PairKey pair) {
    registry.counter("summary.generated").increment();
  }

  @Override
  public void failed(FailureKind kind) {
    registry.counter("summary.failed", "kind", kind.name()).increment();
  }

  @Override
  public void readyLatency(Duration latency) {
    registry.timer("summary.ready.latency").record(latency);
  }
}
