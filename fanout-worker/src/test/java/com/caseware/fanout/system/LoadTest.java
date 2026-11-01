package com.caseware.fanout.system;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.fanout.domain.PublishStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** L-23 (NFR-23, NFR-25): throughput is reported, not asserted (targets are "to agree"). */
class LoadTest {

  @Test
  @DisplayName("L-23: 20,000 items through in-memory ports: all terminal, one write per key, throughput printed")
  void twentyThousandItems() {
    int n = 20_000;
    Rig r = new Rig(0, 1_000, d -> d, 500).efs(n, 200).version("v7", 10);
    Runtime rt = Runtime.getRuntime();
    System.gc();
    long heapBefore = rt.totalMemory() - rt.freeMemory();

    long t0 = System.nanoTime();
    r.handler.handle(r.event("p7", "v7"));
    long t1 = System.nanoTime();
    r.settle();
    long t2 = System.nanoTime();

    PublishStatus s = r.tracker.status("p7");
    assertThat(s.total()).isEqualTo(n);
    assertThat(s.done()).isEqualTo(n);
    assertThat(s.complete()).isTrue();
    assertThat(r.durable.firmsWithQueuedWork(r.clock.instant())).isEmpty();
    assertThat(r.real.totalRecordCalls()).as("one write per key").isEqualTo(n);
    assertThat(r.real.snapshot(Rig.TPL)).hasSize(n);
    for (int i = 0; i < n; i += 97) {
      assertThat(r.real.recordCalls(r.key(i, "v7"))).isEqualTo(1);
    }

    long heapAfter = rt.totalMemory() - rt.freeMemory();
    double fanoutS = (t1 - t0) / 1e9;
    double procS = (t2 - t1) / 1e9;
    System.out.printf(
        "L-23: %d items; fan-out %.2f s (%.0f items/s); dispatch %.2f s (%.0f items/s); heap delta ~%d MB%n",
        n, fanoutS, n / fanoutS, procS, n / procS, (heapAfter - heapBefore) / (1024 * 1024));
  }
}
