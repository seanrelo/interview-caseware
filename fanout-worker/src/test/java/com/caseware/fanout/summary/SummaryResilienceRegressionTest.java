package com.caseware.fanout.summary;

import static com.caseware.fanout.summary.SummaryFixtures.MODEL;
import static com.caseware.fanout.summary.SummaryFixtures.PROMPT;
import static com.caseware.fanout.summary.SummaryFixtures.diff;
import static com.caseware.fanout.summary.SummaryFixtures.pair;
import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.fanout.adapter.memory.FakeModelClient;
import com.caseware.fanout.adapter.memory.InMemoryBaselinesInUse;
import com.caseware.fanout.adapter.memory.InMemoryDiffSource;
import com.caseware.fanout.adapter.memory.InMemorySingleFlight;
import com.caseware.fanout.adapter.memory.InMemorySummaryJobQueue;
import com.caseware.fanout.adapter.memory.InMemorySummaryMetrics;
import com.caseware.fanout.adapter.memory.InMemorySummaryStore;
import com.caseware.fanout.app.MutableClock;
import com.caseware.fanout.graph.ImmutableVersionGraph;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Regression tests for the Spec 05 resilience defects QA-1 to QA-4 (see TEST-MATRIX.md). */
class SummaryResilienceRegressionTest {
  final MutableClock clock = new MutableClock(Instant.parse("2026-11-01T10:00:00Z"));
  final InMemoryDiffSource diffs = new InMemoryDiffSource();
  final FakeModelClient model = new FakeModelClient();
  final InMemorySingleFlight flight = new InMemorySingleFlight(clock);
  final InMemorySummaryMetrics metrics = new InMemorySummaryMetrics();
  final SummaryProperties props =
      new SummaryProperties(MODEL, PROMPT, 5, Duration.ofMinutes(2), Duration.ofSeconds(10), Duration.ofSeconds(25), 0.01, 0.05);
  final PairKey p = pair("v1", "v2");

  @BeforeEach
  void setUp() {
    diffs.register(diff(p));
  }

  SummaryService build(SummaryStore store, SummaryJobQueue queue) {
    return new SummaryService(store, diffs, model, new SummaryValidator(), flight, queue,
        new InMemoryBaselinesInUse(), () -> new ImmutableVersionGraph(List.of()), props, metrics, clock);
  }

  @Test
  @DisplayName("QA-1 NFR-27: append ok but pointer move fails, the retry must not leave a second record")
  void orphanRecordOnPointerFailure() {
    AtomicInteger moves = new AtomicInteger();
    InMemorySummaryStore store = new InMemorySummaryStore() {
      @Override
      public boolean moveCurrentIfAbsent(PairKey pair, String generationId) {
        if (moves.getAndIncrement() == 0) {
          throw new IllegalStateException("pointer store down");
        }
        return super.moveCurrentIfAbsent(pair, generationId);
      }
    };
    InMemorySummaryJobQueue queue = new InMemorySummaryJobQueue(clock);
    SummaryService s = build(store, queue);
    s.ensure(p, JobReason.ON_DEMAND);
    s.processNext();
    clock.advance(Duration.ofSeconds(10));
    s.processNext();
    assertThat(s.find(p)).isPresent();
    assertThat(store.history(p)).as("one pair, one stored record").hasSize(1);
  }

  @Test
  @DisplayName("QA-2 NFR-27: at-least-once redelivery plus lease takeover must not append a second record")
  void leaseTakeoverLeavesOrphan() {
    InMemorySummaryStore store = new InMemorySummaryStore();
    SummaryJob job = new SummaryJob(p, JobReason.ON_DEMAND, 1);
    // A queue that redelivers the same job (SQS-style visibility timeout) while it is still in flight.
    InMemorySummaryJobQueue queue = new InMemorySummaryJobQueue(clock) {
      @Override
      public synchronized Optional<SummaryJob> poll() {
        return Optional.of(job);
      }
    };
    SummaryService s = build(store, queue);
    model.beforeGenerate(r -> {
      if (model.calls() == 1) { // worker A is slow: its lease expires, worker B takes over and finishes
        clock.advance(Duration.ofMinutes(3));
        s.processNext();
      }
    });
    s.processNext();
    assertThat(model.calls()).isEqualTo(2); // inherent without fencing
    assertThat(s.find(p)).isPresent();
    assertThat(store.history(p)).as("late worker must not leave an orphan record").hasSize(1);
  }

  @Test
  @DisplayName("QA-3 FR-35 NFR-20: a job dropped because the lease holder crashed is never redone")
  void jobLostWhenLeaseHeldByDeadWorker() {
    InMemorySummaryStore store = new InMemorySummaryStore();
    InMemorySummaryJobQueue queue = new InMemorySummaryJobQueue(clock);
    SummaryService s = build(store, queue);
    flight.tryAcquire(p, Duration.ofMinutes(2)).orElseThrow(); // holder dies, never releases
    s.ensure(p, JobReason.PUBLISH);
    s.processNext();
    clock.advance(Duration.ofMinutes(3)); // lease expired
    while (s.processNext() > 0) {
      clock.advance(Duration.ofMinutes(1));
    }
    assertThat(s.find(p)).as("precomputed summary must eventually exist without a user opening the EF").isPresent();
  }

  @Test
  @DisplayName("QA-4 NFR-32 NFR-30: a non-RuntimeException Error from the model must not strand the pair forever")
  void errorStrandsJob() {
    InMemorySummaryStore store = new InMemorySummaryStore();
    InMemorySummaryJobQueue queue = new InMemorySummaryJobQueue(clock);
    SummaryService s = build(store, queue);
    AtomicInteger n = new AtomicInteger();
    model.beforeGenerate(r -> {
      if (n.getAndIncrement() == 0) {
        throw new NoSuchMethodError("sdk linkage problem");
      }
    });
    s.ensure(p, JobReason.ON_DEMAND);
    try {
      s.processNext();
    } catch (Error expected) {
      // propagating is acceptable; losing the job is not
    }
    clock.advance(Duration.ofHours(1));
    s.ensure(p, JobReason.ON_DEMAND);
    while (s.processNext() > 0) {
      clock.advance(Duration.ofMinutes(1));
    }
    assertThat(s.find(p)).as("the pair must recover on a later request").isPresent();
  }

  @Test
  @DisplayName("NFR-27: a late worker whose model output differs adds no second record; first current wins")
  void lateWorkerWithDifferentOutputStoresNothingNew() {
    InMemorySummaryStore store = new InMemorySummaryStore();
    SummaryJob job = new SummaryJob(p, JobReason.ON_DEMAND, 1);
    InMemorySummaryJobQueue queue = new InMemorySummaryJobQueue(clock) {
      @Override
      public synchronized Optional<SummaryJob> poll() {
        return Optional.of(job);
      }
    };
    SummaryService s = build(store, queue);
    model.beforeGenerate(r -> {
      if (model.calls() == 1) {
        clock.advance(Duration.ofMinutes(3));
        s.processNext();
      }
    });
    s.processNext();
    SummaryRecord current = s.find(p).orElseThrow();
    assertThat(store.history(p)).hasSize(1);
    assertThat(store.history(p).get(0).generationId()).isEqualTo(current.generationId());
  }

  @Test
  @DisplayName("FR-35: lease busy defers the job without burning an attempt")
  void leaseBusyDoesNotBurnAttempt() {
    InMemorySummaryStore store = new InMemorySummaryStore();
    InMemorySummaryJobQueue queue = new InMemorySummaryJobQueue(clock);
    SummaryService s = build(store, queue);
    flight.tryAcquire(p, Duration.ofMinutes(2)).orElseThrow();
    s.ensure(p, JobReason.PUBLISH);
    for (int i = 0; i < 9; i++) { // more than maxAttempts (5), inside the 2 min lease
      s.processNext();
      clock.advance(Duration.ofSeconds(10));
    }
    assertThat(queue.deadLetters()).isEmpty();
    assertThat(queue.depth()).isEqualTo(1);
    assertThat(model.calls()).isZero();
  }

  @Test
  @DisplayName("NFR-30: an Error from the model counts as a failed attempt and dead-letters after maxAttempts")
  void persistentErrorDeadLetters() {
    InMemorySummaryJobQueue queue = new InMemorySummaryJobQueue(clock);
    SummaryService s = build(new InMemorySummaryStore(), queue);
    model.beforeGenerate(r -> {
      throw new NoSuchMethodError("sdk");
    });
    s.ensure(p, JobReason.ON_DEMAND);
    for (int i = 0; i < 10; i++) {
      s.processNext();
      clock.advance(Duration.ofMinutes(1));
    }
    assertThat(queue.deadLetters()).hasSize(1);
    assertThat(queue.depth()).isZero();
    assertThat(s.find(p)).isEmpty();
  }

  @Test
  @DisplayName("NFR-30: a VirtualMachineError is rethrown after the lease is released and the job requeued")
  void vmErrorRethrownAfterCleanup() {
    InMemorySummaryJobQueue queue = new InMemorySummaryJobQueue(clock);
    SummaryService s = build(new InMemorySummaryStore(), queue);
    model.beforeGenerate(r -> {
      throw new OutOfMemoryError("test");
    });
    s.ensure(p, JobReason.ON_DEMAND);
    org.assertj.core.api.Assertions.assertThatThrownBy(s::processNext).isInstanceOf(OutOfMemoryError.class);
    assertThat(flight.tryAcquire(p, Duration.ofMinutes(1))).as("lease released").isPresent();
    assertThat(queue.depth()).isEqualTo(1);
  }
}
