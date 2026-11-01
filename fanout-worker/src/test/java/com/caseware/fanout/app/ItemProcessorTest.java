package com.caseware.fanout.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.caseware.fanout.adapter.memory.InMemoryFirmFlags;
import com.caseware.fanout.adapter.memory.InMemoryPendingUpdateMarker;
import com.caseware.fanout.adapter.memory.InMemoryVersionGraphReader;
import com.caseware.fanout.adapter.memory.InMemoryWorkItemStore;
import com.caseware.fanout.domain.EfId;
import com.caseware.fanout.domain.FirmId;
import com.caseware.fanout.domain.ItemState;
import com.caseware.fanout.domain.SkipReason;
import com.caseware.fanout.domain.TemplateId;
import com.caseware.fanout.domain.VersionId;
import com.caseware.fanout.domain.WorkItem;
import com.caseware.fanout.domain.WorkKey;
import com.caseware.fanout.port.DownstreamException;
import com.caseware.fanout.port.DownstreamThrottledException;
import com.caseware.fanout.port.MarkResult;
import com.caseware.fanout.port.PendingUpdateMarker;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ItemProcessorTest {

  private static final Instant T0 = Instant.parse("2026-10-30T10:00:00Z");
  private static final Duration LEASE = Duration.ofSeconds(30);
  private static final TemplateId TPL = new TemplateId("audit-ca");
  private static final VersionId V5 = new VersionId("v5");
  private static final VersionId V7 = new VersionId("v7");
  private static final FirmId FIRM = new FirmId("f1");

  private final MutableClock clock = new MutableClock(T0);
  private final InMemoryWorkItemStore store = new InMemoryWorkItemStore();
  private final InMemoryVersionGraphReader graph = new InMemoryVersionGraphReader();
  private final InMemoryPendingUpdateMarker marker = new InMemoryPendingUpdateMarker(graph);
  private final InMemoryFirmFlags flags = new InMemoryFirmFlags();

  @BeforeEach
  void graph() {
    graph.addVersion(TPL, V5, T0.minusSeconds(100), true);
    graph.addVersion(TPL, V7, T0.minusSeconds(10), true);
  }

  private static WorkKey key(int i, VersionId v) {
    return new WorkKey(new EfId("ef-" + i), TPL, v);
  }

  private WorkKey create(int i, VersionId v, FirmId firm) {
    WorkKey k = key(i, v);
    assertThat(store.createIfAbsent(WorkItem.pending(k, firm, "p-" + v.value(), T0))).isTrue();
    return k;
  }

  private ItemProcessor processor(PendingUpdateMarker m) {
    return new ItemProcessor(store, m, flags, clock);
  }

  private Dispatcher dispatcher(ItemProcessor p, int batch) {
    return new Dispatcher(store, p, clock, LEASE, batch);
  }

  private ItemState state(WorkKey k) {
    return store.find(k).orElseThrow().state();
  }

  @Test
  @DisplayName("FR-16: the marker is called exactly once per key and the item ends DONE")
  void markerCalledOncePerKey() {
    List<WorkKey> keys = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      keys.add(create(i, V7, FIRM));
    }

    int leased = dispatcher(processor(marker), 4).drain();

    assertThat(leased).isEqualTo(10);
    keys.forEach(
        k -> {
          assertThat(marker.recordCalls(k)).isEqualTo(1);
          assertThat(state(k)).isEqualTo(ItemState.DONE);
        });
    assertThat(marker.totalRecordCalls()).isEqualTo(10);
  }

  @Test
  @DisplayName("FR-17: a replayed key creates nothing, leaves state equal and the DONE item is not re-invoked")
  void replayedKeyLeavesStateEqual() {
    WorkKey k = create(1, V7, FIRM);
    Dispatcher d = dispatcher(processor(marker), 10);
    d.drain();
    var before = marker.status(k.ef(), TPL);
    WorkItem done = store.find(k).orElseThrow();

    boolean created = store.createIfAbsent(WorkItem.pending(k, FIRM, "p-again", T0));
    clock.advance(LEASE.multipliedBy(10));
    int leased = d.drain();

    assertThat(created).isFalse();
    assertThat(leased).isZero();
    assertThat(marker.recordCalls(k)).isEqualTo(1);
    assertThat(marker.status(k.ef(), TPL)).isEqualTo(before);
    assertThat(store.find(k).orElseThrow()).isEqualTo(done);
  }

  @Test
  @DisplayName("FR-17: marker UNCHANGED (already recorded) counts as success")
  void unchangedIsSuccess() {
    WorkKey k = create(1, V7, FIRM);
    marker.record(k); // someone (reconciliation) wrote it before
    ItemProcessor p = processor(marker);

    dispatcher(p, 5).drain();

    assertThat(state(k)).isEqualTo(ItemState.DONE);
  }

  @Test
  @DisplayName("NFR-10: restart over the same store loses nothing and repeats no DONE item")
  void restartLosesNothing() {
    List<WorkKey> keys = new ArrayList<>();
    for (int i = 0; i < 12; i++) {
      keys.add(create(i, V7, FIRM));
    }
    // First worker completes one batch of 5, then "crashes".
    assertThat(dispatcher(processor(marker), 5).runOnce()).isEqualTo(5);
    // A second worker leases a batch and dies before processing it.
    List<WorkItem> orphaned = store.leaseReady(clock.instant(), LEASE, 3, java.util.Set.of());
    assertThat(orphaned).hasSize(3);

    // Restart: fresh processor and dispatcher over the same durable store and marker.
    Dispatcher restarted = dispatcher(processor(marker), 4);
    assertThat(restarted.drain()).isEqualTo(4); // 12 - 5 done - 3 still leased
    clock.advance(LEASE);
    assertThat(restarted.drain()).isEqualTo(3); // orphaned leases expired

    keys.forEach(
        k -> {
          assertThat(state(k)).isEqualTo(ItemState.DONE);
          assertThat(marker.recordCalls(k)).isEqualTo(1);
        });
    assertThat(marker.totalRecordCalls()).isEqualTo(12);
  }

  @Test
  @DisplayName("NFR-10/FR-17: crash after the marker write but before completion converges to equal state")
  void crashAfterMarkerBeforeComplete() {
    WorkKey k = create(1, V7, FIRM);
    store.leaseReady(clock.instant(), LEASE, 1, java.util.Set.of());
    marker.record(k); // write happened, then the worker died
    var before = marker.status(k.ef(), TPL);

    clock.advance(LEASE);
    dispatcher(processor(marker), 1).drain();

    assertThat(state(k)).isEqualTo(ItemState.DONE);
    assertThat(marker.status(k.ef(), TPL)).isEqualTo(before);
    assertThat(marker.recordCalls(k)).isEqualTo(2);
  }

  @Test
  @DisplayName("NFR-10: an expired lease is re-leased, an active one is not")
  void expiredLeaseReLeased() {
    create(1, V7, FIRM);
    assertThat(store.leaseReady(clock.instant(), LEASE, 5, java.util.Set.of())).hasSize(1);

    clock.advance(LEASE.minusSeconds(1));
    assertThat(store.leaseReady(clock.instant(), LEASE, 5, java.util.Set.of())).isEmpty();

    clock.advance(Duration.ofSeconds(1));
    assertThat(store.leaseReady(clock.instant(), LEASE, 5, java.util.Set.of())).hasSize(1);
  }

  @Test
  @DisplayName("NFR-10: a stale holder cannot complete an item after its lease moved on")
  void staleHolderCannotComplete() {
    WorkKey k = create(1, V7, FIRM);
    WorkItem first = store.leaseReady(clock.instant(), LEASE, 1, java.util.Set.of()).get(0);
    clock.advance(LEASE);
    WorkItem second = store.leaseReady(clock.instant(), LEASE, 1, java.util.Set.of()).get(0);

    ItemProcessor.Outcome staleOutcome = processor(marker).process(first);
    ItemProcessor.Outcome liveOutcome = processor(marker).process(second);

    assertThat(staleOutcome).isEqualTo(ItemProcessor.Outcome.STALE);
    assertThat(liveOutcome).isEqualTo(ItemProcessor.Outcome.DONE);
    assertThat(state(k)).isEqualTo(ItemState.DONE);
  }

  @Test
  @DisplayName("NFR-27: concurrent workers on one store never process an item twice")
  void concurrentWorkersNoDoubleProcessing() throws Exception {
    int items = 600;
    int workers = 8;
    for (int i = 0; i < items; i++) {
      create(i, V7, new FirmId("f" + i % 5));
    }
    ItemProcessor p = processor(marker);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(workers);
    try {
      List<Future<Integer>> results = new ArrayList<>();
      for (int w = 0; w < workers; w++) {
        Dispatcher d = dispatcher(p, 7);
        results.add(
            pool.submit(
                () -> {
                  start.await();
                  return d.drain();
                }));
      }
      start.countDown();
      int leasedTotal = 0;
      for (Future<Integer> f : results) {
        leasedTotal += f.get();
      }
      assertThat(leasedTotal).isEqualTo(items);
    } finally {
      pool.shutdownNow();
    }

    assertThat(marker.totalRecordCalls()).isEqualTo(items);
    for (int i = 0; i < items; i++) {
      assertThat(marker.recordCalls(key(i, V7))).isEqualTo(1);
      assertThat(state(key(i, V7))).isEqualTo(ItemState.DONE);
    }
  }

  @Test
  @DisplayName("NFR-30: a throwing downstream never propagates and never yields DONE")
  void downstreamFailureDoesNotPropagate() {
    WorkKey k1 = create(1, V7, FIRM);
    WorkKey k2 = create(2, V7, FIRM);
    WorkKey k3 = create(3, V7, FIRM);
    AtomicInteger calls = new AtomicInteger();
    PendingUpdateMarker failing =
        new PendingUpdateMarker() {
          @Override
          public MarkResult record(WorkKey key) {
            int n = calls.incrementAndGet();
            if (n == 1) {
              throw new DownstreamThrottledException("429");
            }
            if (n == 2) {
              throw new DownstreamException("500");
            }
            throw new IllegalStateException("bug in client");
          }

          @Override
          public void markUnknown(WorkKey key, String internalReason) {}

          @Override
          public MarkResult reevaluate(WorkKey w, com.caseware.fanout.graph.EligibleResult r) {
            throw new UnsupportedOperationException();
          }
        };
    Dispatcher d = dispatcher(processor(failing), 10);

    assertThatCode(d::drain).doesNotThrowAnyException();

    for (WorkKey k : List.of(k1, k2, k3)) {
      assertThat(state(k)).isEqualTo(ItemState.LEASED);
    }
    // Not DONE, hence never counted as up to date; re-leasable once the lease expires.
    clock.advance(LEASE);
    assertThat(store.leaseReady(clock.instant(), LEASE, 10, java.util.Set.of())).hasSize(3);
  }

  @Test
  @DisplayName("NFR-30: the failure handler (T4 seam) receives the failure; a throwing handler is contained")
  void failureHandlerSeam() {
    WorkKey k = create(1, V7, FIRM);
    List<String> seen = new CopyOnWriteArrayList<>();
    PendingUpdateMarker failing =
        new PendingUpdateMarker() {
          @Override
          public MarkResult record(WorkKey key) {
            throw new DownstreamThrottledException("429");
          }

          @Override
          public void markUnknown(WorkKey key, String internalReason) {}

          @Override
          public MarkResult reevaluate(WorkKey w, com.caseware.fanout.graph.EligibleResult r) {
            throw new UnsupportedOperationException();
          }
        };
    FailureHandler handler =
        (item, failure) -> {
          seen.add(failure.getClass().getSimpleName());
          store.reschedule(item, item.attempts() + 1, clock.instant().plusSeconds(5));
        };
    ItemProcessor p =
        new ItemProcessor(
            store, failing, flags, clock, SupersessionCheck.NEVER, handler, CallGate.UNLIMITED,
            TerminalListener.NONE);

    dispatcher(p, 5).runOnce();

    assertThat(seen).containsExactly("DownstreamThrottledException");
    WorkItem item = store.find(k).orElseThrow();
    assertThat(item.state()).isEqualTo(ItemState.PENDING);
    assertThat(item.attempts()).isEqualTo(1);

    FailureHandler boom =
        (i, f) -> {
          throw new IllegalStateException("handler bug");
        };
    ItemProcessor p2 =
        new ItemProcessor(
            store, failing, flags, clock, SupersessionCheck.NEVER, boom, CallGate.UNLIMITED,
            TerminalListener.NONE);
    clock.advance(Duration.ofSeconds(5));
    assertThatCode(() -> dispatcher(p2, 5).runOnce()).doesNotThrowAnyException();
    assertThat(state(k)).isEqualTo(ItemState.LEASED);
  }

  @Test
  @DisplayName("NFR-41 (U-41): flag off ends SKIPPED(FLAG_OFF) with no marker call")
  void flagOffSkips() {
    switchOffFirm();
    WorkKey off = create(1, V7, new FirmId("off"));
    WorkKey on = create(2, V7, FIRM);
    List<ItemState> terminals = new CopyOnWriteArrayList<>();
    ItemProcessor p =
        new ItemProcessor(
            store, marker, flags, clock, SupersessionCheck.NEVER, FailureHandler.NONE,
            CallGate.UNLIMITED, (item, t) -> terminals.add(t));

    dispatcher(p, 10).drain();

    WorkItem skipped = store.find(off).orElseThrow();
    assertThat(skipped.state()).isEqualTo(ItemState.SKIPPED);
    assertThat(skipped.skipReason()).isEqualTo(SkipReason.FLAG_OFF);
    assertThat(marker.recordCalls(off)).isZero();
    assertThat(marker.status(off.ef(), TPL)).isEmpty();
    assertThat(state(on)).isEqualTo(ItemState.DONE);
    assertThat(terminals).containsExactlyInAnyOrder(ItemState.SKIPPED, ItemState.DONE);
  }

  private void switchOffFirm() {
    flags.setEnabled(new FirmId("off"), false);
  }

  @Test
  @DisplayName("NFR-12: out-of-order versions give the newer target regardless of arrival order")
  void outOfOrderVersions() {
    for (boolean newerFirst : new boolean[] {true, false}) {
      InMemoryWorkItemStore s = new InMemoryWorkItemStore();
      InMemoryPendingUpdateMarker m = new InMemoryPendingUpdateMarker(graph);
      WorkKey k5 = new WorkKey(new EfId("ef-x"), TPL, V5);
      WorkKey k7 = new WorkKey(new EfId("ef-x"), TPL, V7);
      List<WorkKey> order = newerFirst ? List.of(k7, k5) : List.of(k5, k7);
      for (WorkKey k : order) {
        s.createIfAbsent(WorkItem.pending(k, FIRM, "p", T0));
        new Dispatcher(s, new ItemProcessor(s, m, flags, clock), clock, LEASE, 1).runOnce();
      }
      // duplicate of v5 arriving last must not regress it either
      s.createIfAbsent(WorkItem.pending(k5, FIRM, "p", T0));

      assertThat(m.status(k5.ef(), TPL))
          .contains(new InMemoryPendingUpdateMarker.Status(V7, false));
      assertThat(s.find(k5).orElseThrow().state()).isEqualTo(ItemState.DONE);
      assertThat(s.find(k7).orElseThrow().state()).isEqualTo(ItemState.DONE);
    }
  }

  @Test
  @DisplayName("FR-20 seam: a superseded verdict skips with SUPERSEDED and makes no call")
  void supersessionSeam() {
    WorkKey k = create(1, V5, FIRM);
    ItemProcessor p =
        new ItemProcessor(
            store, marker, flags, clock, item -> true, FailureHandler.NONE, CallGate.UNLIMITED,
            TerminalListener.NONE);

    dispatcher(p, 5).drain();

    assertThat(store.find(k).orElseThrow().skipReason()).isEqualTo(SkipReason.SUPERSEDED);
    assertThat(state(k)).isEqualTo(ItemState.SKIPPED);
    assertThat(marker.totalRecordCalls()).isZero();
  }

  @Test
  @DisplayName("NFR-8 seam: no permit defers the item without a call or an attempt, permit is released")
  void gateSeam() {
    WorkKey k = create(1, V7, FIRM);
    List<CallGate.Outcome> released = new ArrayList<>();
    boolean[] open = {false};
    CallGate gate =
        item -> open[0] ? Optional.of(released::add) : Optional.empty();
    ItemProcessor p =
        new ItemProcessor(
            store, marker, flags, clock, SupersessionCheck.NEVER, FailureHandler.NONE, gate,
            TerminalListener.NONE);
    Dispatcher d = dispatcher(p, 5);

    assertThat(d.drain()).isEqualTo(1); // terminates although the item was deferred
    assertThat(marker.totalRecordCalls()).isZero();
    assertThat(store.find(k).orElseThrow().attempts()).isZero();
    assertThat(state(k)).isEqualTo(ItemState.PENDING);

    open[0] = true;
    d.drain();
    assertThat(released).containsExactly(CallGate.Outcome.SUCCESS);
    assertThat(state(k)).isEqualTo(ItemState.DONE);
  }
}
