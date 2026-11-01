package com.caseware.fanout.system;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.fanout.adapter.memory.InMemoryPendingUpdateMarker.Status;
import com.caseware.fanout.app.Dispatcher;
import com.caseware.fanout.app.ItemProcessor;
import com.caseware.fanout.domain.ItemState;
import com.caseware.fanout.domain.WorkItem;
import com.caseware.fanout.domain.WorkKey;
import com.caseware.fanout.port.MarkResult;
import com.caseware.fanout.port.PendingUpdateMarker;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** F-concurrent and F-stale-lease (NFR-10, FR-17). */
class ConcurrencyAndLeaseTest {

  /** Records every result the downstream returned, in call order. */
  private static final class Recording implements PendingUpdateMarker {
    final PendingUpdateMarker inner;
    final List<MarkResult> results = new CopyOnWriteArrayList<>();

    Recording(PendingUpdateMarker inner) {
      this.inner = inner;
    }

    @Override
    public MarkResult record(WorkKey key) {
      MarkResult r = inner.record(key);
      results.add(r);
      return r;
    }

    @Override
    public void markUnknown(WorkKey key, String internalReason) {
      inner.markUnknown(key, internalReason);
    }

    @Override
    public MarkResult reevaluate(WorkKey w, com.caseware.fanout.graph.EligibleResult r) {
      return inner.reevaluate(w, r);
    }
  }

  @Test
  @DisplayName("F-concurrent: 8 workers on one store, each key is in a marker call at most once at a time and written once")
  void eightWorkersNoKeyInTwoCallsAtOnce() throws Exception {
    int items = 2_000;
    Rig r = new Rig(0, 13, d -> d, 100).efs(items, 25).version("v7", 10);
    r.handler.handle(r.event("p7", "v7"));
    ConcurrentHashMap<WorkKey, AtomicInteger> active = new ConcurrentHashMap<>();
    AtomicInteger overlaps = new AtomicInteger();
    r.marker.latency(
        k -> {
          if (active.computeIfAbsent(k, x -> new AtomicInteger()).incrementAndGet() > 1) {
            overlaps.incrementAndGet();
          }
          Thread.onSpinWait();
          active.get(k).decrementAndGet();
        });

    ExecutorService pool = Executors.newFixedThreadPool(8);
    try {
      CountDownLatch go = new CountDownLatch(1);
      List<Future<Integer>> fs = new ArrayList<>();
      for (int w = 0; w < 8; w++) {
        fs.add(pool.submit(() -> { go.await(); return r.dispatcher.drain(); }));
      }
      go.countDown();
      int leased = 0;
      for (Future<Integer> f : fs) {
        leased += f.get(60, TimeUnit.SECONDS);
      }
      assertThat(leased).isEqualTo(items);
    } finally {
      pool.shutdownNow();
    }
    assertThat(overlaps.get()).isZero();
    for (int i = 0; i < items; i++) {
      assertThat(r.marker.delegatedFor(r.key(i, "v7"))).isEqualTo(1);
      assertThat(r.state(r.key(i, "v7"))).isEqualTo(ItemState.DONE);
    }
    assertThat(r.tracker.status("p7").done()).isEqualTo(items);
    assertThat(r.tracker.status("p7").complete()).isTrue();
  }

  @Test
  @DisplayName("F-concurrent: lease expires while worker A is inside the call; B reclaims and completes; A cannot double-complete (real threads)")
  void leaseExpiryReclaimWithRealThreads() throws Exception {
    Rig r = new Rig(0, 1, d -> d, 10).efs(1, 1).version("v7", 10);
    WorkKey k = r.key(0, "v7");
    r.handler.handle(r.event("p7", "v7"));
    CountDownLatch aInCall = new CountDownLatch(1);
    CountDownLatch releaseA = new CountDownLatch(1);
    r.marker.latency(
        key -> {
          if (aInCall.getCount() == 1) { // first caller is A
            aInCall.countDown();
            try {
              if (!releaseA.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("A never released");
              }
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
        });
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<Integer> a = pool.submit(() -> r.dispatcher.runOnce());
      assertThat(aInCall.await(30, TimeUnit.SECONDS)).isTrue();
      r.clock.advance(Rig.LEASE.plusSeconds(1)); // A's lease expires while it is still in the call
      assertThat(r.dispatcher.runOnce()).as("B reclaims the item").isEqualTo(1);
      assertThat(r.state(k)).isEqualTo(ItemState.DONE);
      releaseA.countDown();
      assertThat(a.get(30, TimeUnit.SECONDS)).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
    assertThat(r.state(k)).isEqualTo(ItemState.DONE);
    assertThat(r.tracker.status("p7").done()).as("counted once, not by the stale holder").isEqualTo(1);
    assertThat(r.marker.delegatedFor(k)).as("at-least-once: both holders reached the marker").isEqualTo(2);
    assertThat(r.real.status(Rig.ef(0), Rig.TPL)).contains(new Status(Rig.v("v7"), false));
  }

  @Test
  @DisplayName("F-stale-lease: second worker completes, first worker's complete is refused and its marker write is UNCHANGED")
  void staleHolderIsRefusedAndItsWriteIsANoOp() {
    Rig r = new Rig(0, 1, d -> d, 10, m -> new Recording(m)).efs(1, 1).version("v7", 10);
    Recording rec = (Recording) r.downstream;
    WorkKey k = r.key(0, "v7");
    r.handler.handle(r.event("p7", "v7"));

    WorkItem first = r.durable.leaseReady(r.clock.instant(), Rig.LEASE, 1, java.util.Set.of()).get(0);
    r.clock.advance(Rig.LEASE.plusSeconds(1)); // first worker is slow: lease expired
    WorkItem second = r.durable.leaseReady(r.clock.instant(), Rig.LEASE, 1, java.util.Set.of()).get(0);
    assertThat(second.leaseToken()).isNotEqualTo(first.leaseToken());

    assertThat(r.processor.process(second)).isEqualTo(ItemProcessor.Outcome.DONE);
    var before = r.real.status(Rig.ef(0), Rig.TPL);
    assertThat(r.processor.process(first)).isEqualTo(ItemProcessor.Outcome.STALE);

    assertThat(rec.results).containsExactly(MarkResult.APPLIED, MarkResult.UNCHANGED);
    assertThat(r.real.status(Rig.ef(0), Rig.TPL)).isEqualTo(before);
    assertThat(r.durable.complete(first, ItemState.DONE)).isFalse();
    assertThat(r.state(k)).isEqualTo(ItemState.DONE);
    assertThat(r.tracker.status("p7").done()).isEqualTo(1);
  }

}
