package com.caseware.fanout.capture;

import com.caseware.fanout.domain.Region;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * Backfill counters per region (NFR-38, ADR 0003). Attempted, recorded and failed are reported
 * separately: NFR-5 is read as "100% attempted, failures counted".
 */
public final class BackfillMetrics {

  public enum Counter {
    /** EFs seen by enumeration (each EF once, retries do not count again). */
    ENUMERATED,
    /** Loads started (retries count). */
    ATTEMPTED,
    /** Records written by the backfill. */
    RECORDED,
    /** EF already had a record when it was about to be loaded, or the hook won the write. */
    SKIPPED_HOOK_COVERED,
    SKIPPED_FLAG_OFF,
    /** Failed loads or writes (each failed attempt counts). */
    FAILED,
    RETRY_SCHEDULED
  }

  private final Map<Counter, Map<Region, LongAdder>> counters = new EnumMap<>(Counter.class);

  public BackfillMetrics() {
    for (Counter c : Counter.values()) {
      Map<Region, LongAdder> m = new EnumMap<>(Region.class);
      for (Region r : Region.values()) {
        m.put(r, new LongAdder());
      }
      counters.put(c, m);
    }
  }

  public void count(Counter counter, Region region) {
    counters.get(counter).get(region).increment();
  }

  public long get(Counter counter, Region region) {
    return counters.get(counter).get(region).sum();
  }

  public long total(Counter counter) {
    long sum = 0;
    for (Region r : Region.values()) {
      sum += get(counter, r);
    }
    return sum;
  }

  /** (recorded + hook-covered) / enumerated for the region; 0 when nothing was enumerated. */
  public double coverage(Region region) {
    long enumerated = get(Counter.ENUMERATED, region);
    if (enumerated == 0) {
      return 0.0;
    }
    long covered = get(Counter.RECORDED, region) + get(Counter.SKIPPED_HOOK_COVERED, region);
    return (double) covered / enumerated;
  }

  public void bind(MeterRegistry registry) {
    for (Counter c : Counter.values()) {
      for (Region r : Region.values()) {
        FunctionCounter.builder("capture.backfill", this, m -> m.get(c, r))
            .tag("counter", c.name())
            .tag("region", r.name())
            .register(registry);
      }
    }
    for (Region r : Region.values()) {
      Gauge.builder("capture.backfill.coverage", this, m -> m.coverage(r))
          .tag("region", r.name())
          .register(registry);
    }
  }
}
