package com.caseware.fanout.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.fanout.adapter.memory.InMemoryDeadLetterSink;
import com.caseware.fanout.adapter.memory.InMemoryFirmFlags;
import com.caseware.fanout.adapter.memory.InMemoryPendingUpdateMarker;
import com.caseware.fanout.adapter.memory.InMemoryVersionGraphReader;
import com.caseware.fanout.adapter.memory.InMemoryWorkItemStore;
import com.caseware.fanout.domain.DeadLetter;
import com.caseware.fanout.domain.EfId;
import com.caseware.fanout.domain.FirmId;
import com.caseware.fanout.domain.ItemState;
import com.caseware.fanout.domain.TemplateId;
import com.caseware.fanout.domain.VersionId;
import com.caseware.fanout.domain.WorkItem;
import com.caseware.fanout.domain.WorkKey;
import com.caseware.fanout.fault.FlakyMarker;
import com.caseware.fanout.limiter.RetryPolicy;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RetryingFailureHandlerTest {

  private static final Instant T0 = Instant.parse("2026-10-30T10:00:00Z");
  private static final TemplateId TPL = new TemplateId("tpl");
  private static final VersionId V5 = new VersionId("v5");
  private static final VersionId V7 = new VersionId("v7");
  private static final FirmId FIRM = new FirmId("firm-1");
  private static final Duration LEASE = Duration.ofMinutes(5);
  private static final RetryPolicy POLICY =
      new RetryPolicy(5, Duration.ofSeconds(1), Duration.ofSeconds(30));

  private final MutableClock clock = new MutableClock(T0);
  private final InMemoryWorkItemStore store = new InMemoryWorkItemStore();
  private final InMemoryVersionGraphReader graph = new InMemoryVersionGraphReader();
  private final InMemoryPendingUpdateMarker real = new InMemoryPendingUpdateMarker(graph);
  private final FlakyMarker marker = new FlakyMarker(real, 1L);
  private final InMemoryDeadLetterSink dlq =
      new InMemoryDeadLetterSink(clock, Duration.ofDays(14));
  private final List<ItemState> terminals = new ArrayList<>();
  private Dispatcher dispatcher;

  @BeforeEach
  void setUp() {
    graph.addVersion(TPL, V5, T0.minusSeconds(100), true);
    graph.addVersion(TPL, V7, T0.minusSeconds(10), true);
    RetryingFailureHandler handler =
        new RetryingFailureHandler(
            store, marker, dlq, POLICY, clock, new SplittableRandom(7), (i, s) -> terminals.add(s));
    ItemProcessor processor =
        new ItemProcessor(
            store,
            marker,
            new InMemoryFirmFlags(),
            clock,
            SupersessionCheck.NEVER,
            handler,
            CallGate.UNLIMITED,
            (i, s) -> terminals.add(s));
    dispatcher = new Dispatcher(store, processor, clock, LEASE, 10);
  }

  private WorkKey create(int i, VersionId v) {
    WorkKey k = new WorkKey(new EfId("ef-" + i), TPL, v);
    store.createIfAbsent(WorkItem.pending(k, FIRM, "p1", T0));
    return k;
  }

  private WorkItem item(WorkKey k) {
    return store.find(k).orElseThrow();
  }

  /** Runs one pass at the item's notBefore and returns that instant. */
  private Instant runAtNotBefore(WorkKey k) {
    Instant nb = item(k).notBefore();
    if (nb.isAfter(clock.instant())) {
      clock.advance(Duration.between(clock.instant(), nb));
    }
    dispatcher.runOnce();
    return nb;
  }

  @Test
  @DisplayName("FR-18: each failure reschedules with attempts+1 and a delay within the jitter window")
  void retriesWithBackoff() {
    WorkKey k = create(1, V7);
    marker.failAlways(k);
    for (int failed = 1; failed <= 4; failed++) {
      Instant before = clock.instant();
      dispatcher.runOnce();
      WorkItem it = item(k);
      assertThat(it.state()).isEqualTo(ItemState.PENDING);
      assertThat(it.attempts()).isEqualTo(failed);
      assertThat(Duration.between(before, it.notBefore()))
          .isBetween(Duration.ZERO, POLICY.ceiling(failed));
      runAtNotBefore(k);
      assertThat(marker.callsFor(k)).isEqualTo(failed + 1);
    }
  }

  @Test
  @DisplayName("FR-18: exactly 5 downstream attempts, then DEAD_LETTERED")
  void fiveAttemptsThenDeadLetter() {
    WorkKey k = create(1, V7);
    marker.failAlways(k);
    for (int i = 0; i < 20 && !item(k).state().terminal(); i++) {
      runAtNotBefore(k);
    }
    assertThat(marker.callsFor(k)).isEqualTo(5);
    assertThat(item(k).state()).isEqualTo(ItemState.DEAD_LETTERED);
  }

  @Test
  @DisplayName("FR-18: an item is not retried before its backoff elapses")
  void notRetriedEarly() {
    WorkKey k = create(1, V7);
    marker.failAlways(k);
    dispatcher.runOnce();
    Instant nb = item(k).notBefore();
    if (nb.isAfter(clock.instant())) {
      assertThat(dispatcher.runOnce()).isZero();
      assertThat(marker.callsFor(k)).isEqualTo(1);
    }
  }

  @Test
  @DisplayName("FR-19, NFR-29: exhaustion writes DLQ entry with timestamp and marks EF unknown")
  void deadLetterAndUnknown() {
    WorkKey k = create(1, V7);
    marker.failAlways(k);
    for (int i = 0; i < 20 && !item(k).state().terminal(); i++) {
      runAtNotBefore(k);
    }
    Instant failedAt = clock.instant();
    assertThat(dlq.depth()).isEqualTo(1);
    DeadLetter dl = dlq.entries().get(0);
    assertThat(dl.key()).isEqualTo(k);
    assertThat(dl.attempts()).isEqualTo(5);
    assertThat(dl.at()).isEqualTo(failedAt);
    assertThat(dlq.expiresAt(dl)).isEqualTo(failedAt.plus(Duration.ofDays(14)));
    InMemoryPendingUpdateMarker.Status s = real.status(k.ef(), TPL).orElseThrow();
    assertThat(s.unknown()).isTrue();
    assertThat(s.target()).isEqualTo(V7);
    assertThat(terminals).containsExactly(ItemState.DEAD_LETTERED);
  }

  @Test
  @DisplayName("FR-19 (F-19): always-failing item ends DLQ + unknown while other items complete")
  void othersUnaffected() {
    WorkKey bad = create(1, V7);
    List<WorkKey> good = new ArrayList<>();
    for (int i = 2; i <= 6; i++) {
      good.add(create(i, V7));
    }
    marker.failAlways(bad);
    for (int i = 0; i < 20; i++) {
      runAtNotBefore(bad);
    }
    assertThat(item(bad).state()).isEqualTo(ItemState.DEAD_LETTERED);
    for (WorkKey g : good) {
      assertThat(item(g).state()).isEqualTo(ItemState.DONE);
      assertThat(real.status(g.ef(), TPL).orElseThrow().unknown()).isFalse();
    }
    assertThat(dlq.depth()).isEqualTo(1);
  }

  @Test
  @DisplayName("FR-18: throttle exceptions are retried the same way and can recover")
  void throttleRetriedLikeErrors() {
    WorkKey k = create(1, V7);
    marker.throttleNext(2);
    for (int i = 0; i < 10 && !item(k).state().terminal(); i++) {
      runAtNotBefore(k);
    }
    assertThat(item(k).state()).isEqualTo(ItemState.DONE);
    assertThat(marker.callsFor(k)).isEqualTo(3);
    assertThat(dlq.depth()).isZero();
  }

  @Test
  @DisplayName("FR-18, FR-19: permanent throttling dead-letters after 5 attempts")
  void throttleExhausts() {
    WorkKey k = create(1, V7);
    marker.throttleAll(true);
    for (int i = 0; i < 20 && !item(k).state().terminal(); i++) {
      runAtNotBefore(k);
    }
    assertThat(marker.callsFor(k)).isEqualTo(5);
    assertThat(item(k).state()).isEqualTo(ItemState.DEAD_LETTERED);
  }

  @Test
  @DisplayName("FR-19, NFR-12: unknown never overwrites a newer pending target")
  void unknownIsMonotonic() {
    WorkKey newer = create(1, V7);
    WorkKey older = new WorkKey(new EfId("ef-1"), TPL, V5);
    store.createIfAbsent(WorkItem.pending(older, FIRM, "p0", T0));
    marker.failAlways(older);
    // v7 applies first, then v5 exhausts its retries
    for (int i = 0; i < 30; i++) {
      runAtNotBefore(older);
    }
    assertThat(item(newer).state()).isEqualTo(ItemState.DONE);
    assertThat(item(older).state()).isEqualTo(ItemState.DEAD_LETTERED);
    InMemoryPendingUpdateMarker.Status s = real.status(newer.ef(), TPL).orElseThrow();
    assertThat(s.target()).isEqualTo(V7);
    assertThat(s.unknown()).isFalse();
  }

  @Test
  @DisplayName("NFR-29: DLQ entry expires only after the retention period")
  void retention() {
    WorkKey k = create(1, V7);
    marker.failAlways(k);
    for (int i = 0; i < 20 && !item(k).state().terminal(); i++) {
      runAtNotBefore(k);
    }
    clock.advance(Duration.ofDays(14).minusSeconds(1));
    assertThat(dlq.depth()).isEqualTo(1);
    clock.advance(Duration.ofSeconds(1));
    assertThat(dlq.depth()).isZero();
  }

  @Test
  @DisplayName("NFR-39, NFR-38: dlq.depth gauge tracks the sink")
  void gauge() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    DeadLetterMetrics.bind(registry, dlq);
    assertThat(registry.get("dlq.depth").gauge().value()).isZero();
    WorkKey k = create(1, V7);
    marker.failAlways(k);
    for (int i = 0; i < 20 && !item(k).state().terminal(); i++) {
      runAtNotBefore(k);
    }
    assertThat(registry.get("dlq.depth").gauge().value()).isEqualTo(1.0);
  }
}
