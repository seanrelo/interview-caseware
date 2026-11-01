package com.caseware.fanout.capture;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/** Plain counters, exposed for tests and bindable to a MeterRegistry (NFR-38). */
public final class CaptureMetrics {

  public enum Event {
    HOOK_APPENDED,
    HOOK_FLAG_OFF,
    HOOK_FAILED,
    FALLBACK_QUEUED,
    FALLBACK_DROPPED,
    FALLBACK_REPLAYED,
    RELAY_APPLIED,
    RELAY_FAILED,
    RELAY_TIMEOUT,
    RELAY_NO_RECORD,
    BREAKER_OPENED,
    BREAKER_SKIPPED,
    DEAD_LETTERED
  }

  private final Map<UpsertResult, LongAdder> results = new EnumMap<>(UpsertResult.class);
  private final Map<Event, LongAdder> events = new EnumMap<>(Event.class);

  public CaptureMetrics() {
    for (UpsertResult r : UpsertResult.values()) {
      results.put(r, new LongAdder());
    }
    for (Event e : Event.values()) {
      events.put(e, new LongAdder());
    }
  }

  public void count(UpsertResult result) {
    results.get(result).increment();
  }

  public void count(Event event) {
    events.get(event).increment();
  }

  public long get(UpsertResult result) {
    return results.get(result).sum();
  }

  public long get(Event event) {
    return events.get(event).sum();
  }

  public void bind(MeterRegistry registry) {
    for (UpsertResult r : UpsertResult.values()) {
      FunctionCounter.builder("capture.upsert", this, m -> m.get(r))
          .tag("result", r.name())
          .register(registry);
    }
    for (Event e : Event.values()) {
      FunctionCounter.builder("capture.event", this, m -> m.get(e))
          .tag("event", e.name())
          .register(registry);
    }
  }
}
