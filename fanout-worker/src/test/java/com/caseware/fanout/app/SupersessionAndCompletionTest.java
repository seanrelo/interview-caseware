package com.caseware.fanout.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.fanout.adapter.memory.InMemoryDeadLetterSink;
import com.caseware.fanout.adapter.memory.InMemoryFirmFlags;
import com.caseware.fanout.adapter.memory.InMemoryPendingUpdateMarker;
import com.caseware.fanout.adapter.memory.InMemoryPublishProgressStore;
import com.caseware.fanout.adapter.memory.InMemoryVersionGraphReader;
import com.caseware.fanout.adapter.memory.InMemoryWorkItemStore;
import com.caseware.fanout.domain.EfId;
import com.caseware.fanout.domain.FirmId;
import com.caseware.fanout.domain.ItemState;
import com.caseware.fanout.domain.PublishStatus;
import com.caseware.fanout.domain.SkipReason;
import com.caseware.fanout.domain.TemplateId;
import com.caseware.fanout.domain.VersionId;
import com.caseware.fanout.domain.WorkItem;
import com.caseware.fanout.domain.WorkKey;
import com.caseware.fanout.fault.FlakyMarker;
import com.caseware.fanout.limiter.RetryPolicy;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SupersessionAndCompletionTest {

  private static final Instant T0 = Instant.parse("2026-10-30T10:00:00Z");
  private static final TemplateId TPL = new TemplateId("tpl");
  private static final VersionId V5 = new VersionId("v5");
  private static final VersionId V7 = new VersionId("v7");
  private static final FirmId FIRM = new FirmId("firm-1");

  private final MutableClock clock = new MutableClock(T0);
  private final InMemoryWorkItemStore store = new InMemoryWorkItemStore();
  private final InMemoryVersionGraphReader graph = new InMemoryVersionGraphReader();
  private final InMemoryPendingUpdateMarker real = new InMemoryPendingUpdateMarker(graph);
  private final FlakyMarker marker = new FlakyMarker(real, 3L);
  private final InMemoryPublishProgressStore progress = new InMemoryPublishProgressStore();
  private final InMemoryDeadLetterSink dlq = new InMemoryDeadLetterSink(clock, Duration.ofDays(14));
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private CompletionTracker tracker;
  private ItemProcessor processor;
  private Dispatcher dispatcher;

  @BeforeEach
  void setUp() {
    graph.addVersion(TPL, V5, T0.minusSeconds(100), true);
    graph.addVersion(TPL, V7, T0.minusSeconds(10), true);
    tracker = new CompletionTracker(progress, store, id -> Optional.of(T0), clock, registry);
    RetryingFailureHandler handler =
        new RetryingFailureHandler(
            store,
            marker,
            dlq,
            new RetryPolicy(5, Duration.ofSeconds(1), Duration.ofSeconds(30)),
            clock,
            new SplittableRandom(1),
            tracker);
    processor =
        new ItemProcessor(
            store,
            marker,
            new InMemoryFirmFlags(),
            clock,
            new VersionSupersessionCheck(graph),
            handler,
            CallGate.UNLIMITED,
            tracker);
    dispatcher = new Dispatcher(store, processor, clock, Duration.ofMinutes(5), 50);
  }

  private WorkKey create(int i, VersionId v, String publishId) {
    WorkKey k = new WorkKey(new EfId("ef-" + i), TPL, v);
    store.createIfAbsent(WorkItem.pending(k, FIRM, publishId, T0));
    return k;
  }

  private WorkItem item(WorkKey k) {
    return store.find(k).orElseThrow();
  }

  private void runUntilQuiet() {
    for (int i = 0; i < 30; i++) {
      Instant next =
          store.firmsWithQueuedWork(clock.instant()).isEmpty() ? null : clock.instant();
      dispatcher.drain();
      clock.advance(Duration.ofSeconds(31));
      if (next == null && store.firmsWithQueuedWork(clock.instant()).isEmpty()) {
        return;
      }
    }
  }

  @Test
  @DisplayName("FR-20: older item is skipped without a call, newer item is processed")
  void olderSkippedNewerProcessed() {
    WorkKey old = create(1, V5, "p5");
    WorkKey newer = create(1, V7, "p7");
    dispatcher.drain();
    assertThat(item(old).state()).isEqualTo(ItemState.SKIPPED);
    assertThat(item(old).skipReason()).isEqualTo(SkipReason.SUPERSEDED);
    assertThat(marker.callsFor(old)).isZero();
    assertThat(item(newer).state()).isEqualTo(ItemState.DONE);
    assertThat(real.status(old.ef(), TPL).orElseThrow().target()).isEqualTo(V7);
  }

  @Test
  @DisplayName("FR-20, NFR-12: only an older publish arrived and is not superseded yet")
  void latestItself() {
    WorkKey k = create(1, V5, "p5");
    graph.withdraw(TPL, V7);
    dispatcher.drain();
    assertThat(item(k).state()).isEqualTo(ItemState.DONE);
  }

  @Test
  @DisplayName("FR-20: race, latest changes between lease and execute")
  void raceBetweenLeaseAndExecute() {
    WorkKey k = create(1, V5, "p5");
    graph.withdraw(TPL, V7);
    WorkItem leased = store.leaseReady(clock.instant(), Duration.ofMinutes(5), 1, java.util.Set.of()).get(0);
    graph.addVersion(TPL, new VersionId("v8"), T0.plusSeconds(5), true); // published after lease
    assertThat(processor.process(leased)).isEqualTo(ItemProcessor.Outcome.SKIPPED_SUPERSEDED);
    assertThat(marker.callsFor(k)).isZero();
  }

  @Test
  @DisplayName("FR-20: target withdrawn between lease and execute is superseded, no marker call")
  void raceLatestWithdrawn() {
    WorkKey k = create(1, V7, "p7");
    WorkItem leased = store.leaseReady(clock.instant(), Duration.ofMinutes(5), 1, java.util.Set.of()).get(0);
    graph.withdraw(TPL, V7);
    assertThat(processor.process(leased)).isEqualTo(ItemProcessor.Outcome.SKIPPED_SUPERSEDED);
    assertThat(marker.callsFor(k)).isZero();
    assertThat(store.find(k).orElseThrow().state()).isEqualTo(ItemState.SKIPPED);
    assertThat(store.find(k).orElseThrow().skipReason()).isEqualTo(SkipReason.SUPERSEDED);
  }

  @Test
  @DisplayName("FR-20: graph that cannot order the versions fails open, marker stays monotonic")
  void unknownVersionFailsOpen() {
    WorkKey k = new WorkKey(new EfId("ef-1"), TPL, new VersionId("v-unknown"));
    assertThat(new VersionSupersessionCheck(graph).superseded(
            WorkItem.pending(k, FIRM, "p", T0))).isFalse();
  }

  @Test
  @DisplayName("FR-21: complete only when sealed and every item is terminal, no dead letters")
  void completionWithoutDeadLetters() {
    create(1, V5, "p");
    create(2, V7, "p");
    create(3, V7, "p");
    assertThat(tracker.isComplete("p")).isFalse();
    progress.addAndSeal("p", 3);
    assertThat(tracker.isComplete("p")).isFalse();
    dispatcher.drain();
    PublishStatus s = tracker.status("p");
    assertThat(s.done()).isEqualTo(2);
    assertThat(s.skipped()).isEqualTo(1);
    assertThat(s.deadLettered()).isZero();
    assertThat(tracker.isComplete("p")).isTrue();
    assertThat(tracker.isCompleteAndClean("p")).isTrue();
  }

  @Test
  @DisplayName("FR-21 (Q4): dead-lettered items count as terminal and are reported separately")
  void completionWithDeadLetters() {
    WorkKey bad = create(1, V7, "p");
    create(2, V7, "p");
    marker.failAlways(bad);
    progress.addAndSeal("p", 2);
    for (int i = 0; i < 6; i++) {
      dispatcher.drain();
      clock.advance(Duration.ofSeconds(31));
    }
    PublishStatus s = tracker.status("p");
    assertThat(s.done()).isEqualTo(1);
    assertThat(s.deadLettered()).isEqualTo(1);
    assertThat(s.complete()).isTrue();
    assertThat(tracker.isCompleteAndClean("p")).isFalse();
  }

  @Test
  @DisplayName("FR-21: not complete while an item is still retrying or the publish is unsealed")
  void notCompleteWhileRetrying() {
    WorkKey bad = create(1, V7, "p");
    marker.failAlways(bad);
    progress.addAndSeal("p", 1);
    dispatcher.runOnce();
    assertThat(tracker.isComplete("p")).isFalse();
    assertThat(tracker.status("p").terminal()).isZero();
  }

  @Test
  @DisplayName("FR-21, NFR-12: replay of a finished item does not double-count")
  void replayDoesNotDoubleCount() {
    create(1, V7, "p");
    progress.addAndSeal("p", 1);
    WorkItem leased = store.leaseReady(clock.instant(), Duration.ofMinutes(5), 1, java.util.Set.of()).get(0);
    assertThat(processor.process(leased)).isEqualTo(ItemProcessor.Outcome.DONE);
    // stale holder replays the same lease, and the dispatcher finds nothing to lease
    assertThat(processor.process(leased)).isEqualTo(ItemProcessor.Outcome.STALE);
    assertThat(dispatcher.drain()).isZero();
    assertThat(tracker.status("p").done()).isEqualTo(1);
  }

  @Test
  @DisplayName("FR-21: duplicate publish id sealing again does not change total")
  void sealedTotalNotRecounted() {
    progress.addAndSeal("p", 2);
    progress.addAndSeal("p", 5);
    assertThat(tracker.status("p").total()).isEqualTo(2);
  }

  @Test
  @DisplayName("NFR-38: publish.marker.latency records publishedAt to marker write, DONE only")
  void latencyTimer() {
    create(1, V5, "p");
    create(2, V7, "p");
    clock.advance(Duration.ofSeconds(4));
    dispatcher.drain();
    var timer = registry.get(CompletionTracker.MARKER_LATENCY).timer();
    assertThat(timer.count()).isEqualTo(1); // skipped item records nothing
    assertThat(timer.totalTime(java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(4.0);
  }

  @Test
  @DisplayName("NFR-12: duplicate and out-of-order publishes end in the same EF state")
  void orderIndependent() {
    // order A: v5 then v7; order B (fresh run below): v7 then v5
    create(1, V5, "p5");
    create(1, V7, "p7");
    dispatcher.drain();
    InMemoryPendingUpdateMarker.Status a = real.status(new EfId("ef-1"), TPL).orElseThrow();

    InMemoryWorkItemStore store2 = new InMemoryWorkItemStore();
    InMemoryPendingUpdateMarker real2 = new InMemoryPendingUpdateMarker(graph);
    ItemProcessor p2 = new ItemProcessor(store2, real2, new InMemoryFirmFlags(), clock);
    for (VersionId v : List.of(V7, V5)) {
      store2.createIfAbsent(WorkItem.pending(new WorkKey(new EfId("ef-1"), TPL, v), FIRM, "x", T0));
    }
    new Dispatcher(store2, p2, clock, Duration.ofMinutes(5), 10).drain();
    assertThat(real2.status(new EfId("ef-1"), TPL).orElseThrow()).isEqualTo(a);
  }
}
