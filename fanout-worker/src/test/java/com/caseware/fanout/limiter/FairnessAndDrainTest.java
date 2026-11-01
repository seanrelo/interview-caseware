package com.caseware.fanout.limiter;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.fanout.adapter.memory.InMemoryFirmFlags;
import com.caseware.fanout.adapter.memory.InMemoryPendingUpdateMarker;
import com.caseware.fanout.adapter.memory.InMemoryVersionGraphReader;
import com.caseware.fanout.adapter.memory.InMemoryWorkItemStore;
import com.caseware.fanout.app.CallGate.Outcome;
import com.caseware.fanout.app.CallGate.Permit;
import com.caseware.fanout.app.Dispatcher;
import com.caseware.fanout.app.FailureHandler;
import com.caseware.fanout.app.ItemProcessor;
import com.caseware.fanout.app.MutableClock;
import com.caseware.fanout.app.SupersessionCheck;
import com.caseware.fanout.app.TerminalListener;
import com.caseware.fanout.domain.EfId;
import com.caseware.fanout.domain.FirmId;
import com.caseware.fanout.domain.ItemState;
import com.caseware.fanout.domain.TemplateId;
import com.caseware.fanout.domain.VersionId;
import com.caseware.fanout.domain.WorkItem;
import com.caseware.fanout.domain.WorkKey;
import com.caseware.fanout.fault.FlakyMarker;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FairnessAndDrainTest {

  private static final Instant T0 = Instant.parse("2026-10-30T10:00:00Z");
  private static final Duration LEASE = Duration.ofSeconds(30);
  private static final TemplateId TPL = new TemplateId("t");
  private static final VersionId V1 = new VersionId("v1");
  private static final FirmId A = new FirmId("A");
  private static final FirmId B = new FirmId("B");
  private static final FirmId C = new FirmId("C");

  private final MutableClock clock = new MutableClock(T0);
  private final InMemoryWorkItemStore store = new InMemoryWorkItemStore();
  private final InMemoryVersionGraphReader graph = new InMemoryVersionGraphReader();
  private final InMemoryPendingUpdateMarker inner = new InMemoryPendingUpdateMarker(graph);
  private final ExecutorService pool = Executors.newFixedThreadPool(12);
  private final AtomicBoolean stop = new AtomicBoolean();

  @BeforeEach
  void graph() {
    graph.addVersion(TPL, V1, T0.minusSeconds(10), true);
  }

  @AfterEach
  void shutdown() {
    stop.set(true);
    pool.shutdownNow();
  }

  private void load(FirmId firm, int n) {
    for (int i = 0; i < n; i++) {
      store.createIfAbsent(
          WorkItem.pending(
              new WorkKey(new EfId(firm.value() + "-" + i), TPL, V1), firm, "p", T0));
    }
  }

  private static FirmId firmOf(WorkKey k) {
    return new FirmId(k.ef().value().substring(0, k.ef().value().indexOf('-')));
  }

  private AdaptiveLimiter limiter(int max) {
    LimiterProperties p = LimiterProperties.defaults(max);
    return new AdaptiveLimiter(
        p, clock, new FirmFairness(p.firmShare(), () -> store.firmsWithQueuedWork(clock.instant())));
  }

  private Dispatcher dispatcher(FlakyMarker marker, AdaptiveLimiter l) {
    ItemProcessor proc =
        new ItemProcessor(
            store,
            marker,
            new InMemoryFirmFlags(),
            clock,
            SupersessionCheck.NEVER,
            FailureHandler.NONE,
            l,
            TerminalListener.NONE);
    return new Dispatcher(store, proc, clock, LEASE, 1, l::saturatedFirms);
  }

  private static void awaitTrue(java.util.function.BooleanSupplier cond) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (!cond.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("condition not reached in 30 s");
      }
      Thread.onSpinWait();
    }
  }

  /** Starts workers whose downstream calls block until {@code release} opens. */
  private record Held(
      AtomicInteger arrived,
      AtomicInteger passes,
      CountDownLatch release,
      Map<FirmId, AtomicInteger> maxByFirm,
      AtomicInteger maxTotal) {}

  private Held startBlockedWorkers(AdaptiveLimiter l, int threads) {
    Held h =
        new Held(
            new AtomicInteger(),
            new AtomicInteger(),
            new CountDownLatch(1),
            new ConcurrentHashMap<>(),
            new AtomicInteger());
    FlakyMarker marker =
        new FlakyMarker(inner, 1L)
            .latency(
                k -> {
                  h.arrived().incrementAndGet();
                  // Sample the limiter's own accounting at the instant the call starts.
                  for (FirmId f : List.of(A, B, C)) {
                    h.maxByFirm()
                        .computeIfAbsent(f, x -> new AtomicInteger())
                        .accumulateAndGet(l.firmInFlight(f), Math::max);
                  }
                  h.maxTotal().accumulateAndGet(l.inFlight(), Math::max);
                  try {
                    h.release().await();
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                });
    Dispatcher d = dispatcher(marker, l);
    for (int t = 0; t < threads; t++) {
      pool.submit(
          () -> {
            while (!stop.get()) {
              d.runOnce();
              h.passes().incrementAndGet();
            }
          });
    }
    return h;
  }

  @Test
  void f11_oneFirmWithTenThousandQueuedIsCappedAtQuarterWhileOthersWait() {
    load(A, 10_000);
    load(B, 500);
    load(C, 500);
    AdaptiveLimiter l = limiter(8); // cap = 2 per firm
    Held h = startBlockedWorkers(l, 12);

    awaitTrue(() -> h.arrived().get() >= 6);
    int passesAtSteady = h.passes().get();
    awaitTrue(() -> h.passes().get() >= passesAtSteady + 200); // plenty of attempts to exceed cap

    assertThat(l.firmInFlight(A)).isEqualTo(2);
    assertThat(l.firmInFlight(B)).isEqualTo(2);
    assertThat(l.firmInFlight(C)).isEqualTo(2);
    assertThat(l.inFlight()).isEqualTo(6);

    h.release().countDown();
    awaitTrue(() -> inner.totalRecordCalls() >= 400);
    stop.set(true);

    assertThat(h.maxByFirm().get(A).get()).isLessThanOrEqualTo(2);
    assertThat(h.maxByFirm().get(B).get()).isLessThanOrEqualTo(2);
    assertThat(h.maxTotal().get()).isLessThanOrEqualTo(8);
  }

  @Test
  void f11_firmCapAppliesAtEveryStepAgainstTenThousandQueuedItems() {
    load(A, 10_000);
    load(B, 1);
    AdaptiveLimiter l = limiter(8);
    List<Permit> held = new ArrayList<>();
    WorkItem a = WorkItem.pending(new WorkKey(new EfId("A-x"), TPL, V1), A, "p", T0);
    for (int i = 0; i < 10_000; i++) {
      Optional<Permit> p = l.tryAcquire(a);
      if (p.isPresent()) {
        held.add(p.get());
      } else {
        held.remove(held.size() - 1).release(Outcome.SUCCESS);
      }
      assertThat(l.firmInFlight(A)).isLessThanOrEqualTo(2);
    }
    assertThat(l.saturatedFirms()).containsExactly(A);
    // The other firm still gets capacity.
    WorkItem b = WorkItem.pending(new WorkKey(new EfId("B-0"), TPL, V1), B, "p", T0);
    assertThat(l.tryAcquire(b)).isPresent();
  }

  @Test
  void f11_loneFirmUsesTheFullLimit() {
    load(A, 200);
    AdaptiveLimiter l = limiter(8);
    Held h = startBlockedWorkers(l, 12);

    awaitTrue(() -> h.arrived().get() >= 8);
    int passesAtSteady = h.passes().get();
    awaitTrue(() -> h.passes().get() >= passesAtSteady + 200);

    assertThat(l.firmInFlight(A)).isEqualTo(8);
    assertThat(l.inFlight()).isEqualTo(8);
    assertThat(l.saturatedFirms()).isEmpty();
    h.release().countDown();
    stop.set(true);
  }

  @Test
  void c8_realDispatchersNeverExceedLimitDownstream() throws Exception {
    load(A, 600);
    load(B, 600);
    AdaptiveLimiter l = limiter(5);
    AtomicLong spins = new AtomicLong();
    FlakyMarker marker =
        new FlakyMarker(inner, 7L)
            .latency(
                k -> {
                  int n = (int) (spins.incrementAndGet() % 300); // varied latency, no sleep
                  for (int s = 0; s < n; s++) {
                    Thread.onSpinWait();
                  }
                });
    Dispatcher d = dispatcher(marker, l);
    CountDownLatch done = new CountDownLatch(10);
    for (int t = 0; t < 10; t++) {
      pool.submit(
          () -> {
            d.drain();
            done.countDown();
          });
    }
    assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
    d.drain();
    assertThat(marker.maxObservedInFlight()).isLessThanOrEqualTo(5);
    assertThat(inner.totalRecordCalls()).isEqualTo(1_200);
    assertThat(l.inFlight()).isZero();
  }

  @Test
  void f32_outageThenDrainEndsWithEveryItemTerminalAtTheSameLimits() {
    load(A, 60);
    load(B, 60);
    AdaptiveLimiter l = limiter(8);
    FlakyMarker marker = new FlakyMarker(inner, 3L).throttleAll(true);
    Dispatcher d = dispatcher(marker, l);

    d.drain(); // outage: every call throttled
    assertThat(l.limit()).isLessThan(8);
    assertThat(inner.totalRecordCalls()).isZero();
    int callsDuringOutage = (int) marker.totalCalls();
    assertThat(callsDuringOutage).as("outage did not hammer downstream beyond the leases").isEqualTo(120);

    marker.throttleAll(false);
    for (int round = 0; round < 200 && !allTerminal(); round++) {
      clock.advance(LEASE.plusSeconds(1)); // leases of failed attempts expire, items are re-leased
      d.drain();
      assertThat(l.limit()).isBetween(1, 8);
    }
    assertThat(allTerminal()).isTrue();
    assertThat(inner.totalRecordCalls()).as("each EF written exactly once").isEqualTo(120);
    assertThat(l.limit()).isEqualTo(8);
    assertThat(l.inFlight()).isZero();
  }

  private boolean allTerminal() {
    for (FirmId f : List.of(A, B)) {
      for (int i = 0; i < 60; i++) {
        ItemState s =
            store.find(new WorkKey(new EfId(f.value() + "-" + i), TPL, V1)).orElseThrow().state();
        if (!s.terminal()) {
          return false;
        }
      }
    }
    return true;
  }
}
