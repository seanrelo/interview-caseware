package com.caseware.fanout.summary;

import static com.caseware.fanout.graph.GraphFixtures.pub;
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
import com.caseware.fanout.adapter.memory.InMemoryVersionRegistry;
import com.caseware.fanout.app.MutableClock;
import com.caseware.fanout.domain.TemplateId;
import com.caseware.fanout.domain.VersionId;
import com.caseware.fanout.graph.GraphPublishEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SummaryServiceTest {
  static final TemplateId T = new TemplateId("T");
  final MutableClock clock = new MutableClock(Instant.parse("2026-11-01T10:00:00Z"));
  final InMemorySummaryStore store = new InMemorySummaryStore();
  final InMemoryDiffSource diffs = new InMemoryDiffSource();
  final FakeModelClient model = new FakeModelClient();
  final InMemorySingleFlight flight = new InMemorySingleFlight(clock);
  final InMemorySummaryJobQueue queue = new InMemorySummaryJobQueue(clock);
  final InMemoryBaselinesInUse baselines = new InMemoryBaselinesInUse();
  final InMemoryVersionRegistry registry = new InMemoryVersionRegistry();
  final InMemorySummaryMetrics metrics = new InMemorySummaryMetrics();
  final SummaryProperties props =
      new SummaryProperties(MODEL, PROMPT, 3, Duration.ofMinutes(2), Duration.ofSeconds(10), Duration.ofSeconds(25), 0.01, 0.05);
  final PairKey p = pair("v1", "v2");
  SummaryService service;

  @BeforeEach
  void setUp() {
    registry.register(pub("v1", 1));
    registry.register(pub("v2", 2, "v1"));
    registry.register(pub("v3", 3, "v2"));
    service = build(store, diffs);
    diffs.register(diff(p));
  }

  SummaryService build(SummaryStore st, DiffSource ds) {
    return new SummaryService(
        st, ds, model, new SummaryValidator(), flight, queue, baselines, registry::snapshot, props, metrics, clock);
  }

  @Test
  @DisplayName("S1 FR-34, FR-36: ensure generates once, a second ensure returns the same record")
  void s1() {
    assertThat(service.ensure(p, JobReason.ON_DEMAND)).isInstanceOf(SummaryOutcome.Generating.class);
    assertThat(service.find(p)).isEmpty();
    assertThat(service.processNext()).isEqualTo(1);
    SummaryRecord first = ((SummaryOutcome.Ready) service.ensure(p, JobReason.ON_DEMAND)).summary();
    SummaryRecord second = ((SummaryOutcome.Ready) service.ensure(p, JobReason.ON_DEMAND)).summary();
    assertThat(second).isSameAs(first).isEqualTo(service.find(p).orElseThrow());
    assertThat(model.calls()).isEqualTo(1);
    assertThat(store.history(p)).hasSize(1);
    assertThat(first.model()).isEqualTo(MODEL);
    assertThat(first.prompt()).isEqualTo(PROMPT);
    assertThat(service.processNext()).isZero();
  }

  @Test
  @DisplayName("FR-37, NFR-37: the model request carries only the pinned model, prompt and diff")
  void modelRequest() {
    service.ensure(p, JobReason.ON_DEMAND);
    service.processNext();
    assertThat(model.captured()).containsExactly(new ModelRequest(MODEL, PROMPT, diff(p)));
  }

  @Test
  @DisplayName("S2 FR-36: 32 threads ensure and process one pair, exactly one model call and one record")
  void s2() throws Exception {
    CountDownLatch go = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(32);
    List<Throwable> errors = new ArrayList<>();
    for (int i = 0; i < 32; i++) {
      Thread.ofVirtual().start(() -> {
        try {
          go.await();
          service.ensure(p, JobReason.ON_DEMAND);
          service.processNext();
        } catch (Throwable t) {
          synchronized (errors) {
            errors.add(t);
          }
        } finally {
          done.countDown();
        }
      });
    }
    go.countDown();
    done.await();
    while (service.processNext() > 0) {
      // drain anything a racing ensure enqueued after the first ack
    }
    assertThat(errors).isEmpty();
    assertThat(model.calls()).isEqualTo(1);
    assertThat(store.history(p)).hasSize(1);
    assertThat(metrics.generatedCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("S2 FR-36: a worker that cannot get the lease makes no model call")
  void leaseHeldElsewhere() {
    Lease other = flight.tryAcquire(p, Duration.ofMinutes(1)).orElseThrow();
    service.ensure(p, JobReason.ON_DEMAND);
    assertThat(service.processNext()).isEqualTo(1);
    assertThat(model.calls()).isZero();
    assertThat(store.history(p)).isEmpty();
    flight.release(other);
  }

  @Test
  @DisplayName("S3 FR-36: a replayed job after success creates no second record")
  void s3() {
    service.ensure(p, JobReason.ON_DEMAND);
    service.processNext();
    queue.enqueue(p, JobReason.RETRY);
    assertThat(service.processNext()).isEqualTo(1);
    assertThat(model.calls()).isEqualTo(1);
    assertThat(store.history(p)).hasSize(1);
    assertThat(queue.depth()).isZero();
  }

  @Test
  @DisplayName("S4 FR-42, NFR-30: model failure stores nothing, retries with backoff, dead-letters after maxAttempts")
  void s4Model() {
    model.failNext(10);
    service.ensure(p, JobReason.ON_DEMAND);
    service.processNext();
    assertThat(store.history(p)).isEmpty();
    assertThat(metrics.failedCount(FailureKind.MODEL_ERROR)).isEqualTo(1);
    assertThat(service.processNext()).isZero(); // backoff 10 s not elapsed
    clock.advance(Duration.ofSeconds(9));
    assertThat(service.processNext()).isZero();
    clock.advance(Duration.ofSeconds(1));
    assertThat(service.processNext()).isEqualTo(1);
    assertThat(metrics.failedCount(FailureKind.MODEL_ERROR)).isEqualTo(2);
    clock.advance(Duration.ofSeconds(19)); // second delay is 20 s
    assertThat(service.processNext()).isZero();
    clock.advance(Duration.ofSeconds(1));
    assertThat(service.processNext()).isEqualTo(1);
    assertThat(model.calls()).isEqualTo(3);
    assertThat(queue.deadLetters()).hasSize(1);
    assertThat(queue.deadLetters().get(0).cause()).contains("MODEL_ERROR");
    assertThat(queue.depth()).isZero();
    assertThat(store.history(p)).isEmpty();
    assertThat(service.find(p)).isEmpty();
  }

  @Test
  @DisplayName("NFR-29: backoff doubles and is capped at maxBackoff")
  void backoffCap() {
    assertThat(service.backoff(1)).isEqualTo(Duration.ofSeconds(10));
    assertThat(service.backoff(2)).isEqualTo(Duration.ofSeconds(20));
    assertThat(service.backoff(3)).isEqualTo(Duration.ofSeconds(25));
    assertThat(service.backoff(60)).isEqualTo(Duration.ofSeconds(25));
  }

  @Test
  @DisplayName("S4 FR-42: a rejected output stores nothing, counts REJECTED and retries")
  void s4Rejected() {
    model.outputOverride(d -> new ModelOutput(List.of(new ModelStatement("Invented.", List.of("nope")))));
    service.ensure(p, JobReason.ON_DEMAND);
    service.processNext();
    assertThat(store.history(p)).isEmpty();
    assertThat(metrics.failedCount(FailureKind.REJECTED)).isEqualTo(1);
    assertThat(queue.depth()).isEqualTo(1);
    assertThat(metrics.generatedCount()).isZero();
  }

  @Test
  @DisplayName("S4 FR-42: absent diff and failing diff source store nothing and count DIFF_UNAVAILABLE")
  void s4Diff() {
    PairKey q = pair("v1", "v3");
    service.ensure(q, JobReason.ON_DEMAND);
    service.processNext();
    assertThat(store.history(q)).isEmpty();
    assertThat(metrics.failedCount(FailureKind.DIFF_UNAVAILABLE)).isEqualTo(1);

    DiffSource broken = pair -> {
      throw new DiffUnavailableException("down");
    };
    SummaryService s2 = build(new InMemorySummaryStore(), broken);
    PairKey r = pair("v2", "v3");
    s2.ensure(r, JobReason.ON_DEMAND);
    s2.processNext();
    assertThat(metrics.failedCount(FailureKind.DIFF_UNAVAILABLE)).isEqualTo(2);
    assertThat(s2.find(r)).isEmpty();
    assertThat(model.calls()).isZero();
  }

  @Test
  @DisplayName("S4 FR-42: a store failure never produces a summary and is retried")
  void s4Store() {
    InMemorySummaryStore failing = new InMemorySummaryStore() {
      @Override
      public SummaryRecord append(SummaryRecord record) {
        throw new IllegalStateException("store down");
      }
    };
    SummaryService s = build(failing, diffs);
    s.ensure(p, JobReason.ON_DEMAND);
    s.processNext();
    assertThat(metrics.failedCount(FailureKind.STORE_ERROR)).isEqualTo(1);
    assertThat(s.find(p)).isEmpty();
    assertThat(queue.depth()).isEqualTo(1);
  }

  @Test
  @DisplayName("S11 FR-35: onPublished enqueues only baselines eligible for the new version, one job per pair, idempotent")
  void s11() {
    baselines.register(T, new VersionId("v1"));
    baselines.register(T, new VersionId("v2"));
    baselines.register(T, new VersionId("v3")); // equals the new version
    baselines.register(T, new VersionId("ghost")); // unknown to the graph
    GraphPublishEvent event = new GraphPublishEvent(T, new VersionId("v3"));
    service.onPublished(event);
    assertThat(queue.depth()).isEqualTo(2);
    assertThat(service.skippedBaselines()).isEqualTo(1);
    service.onPublished(event);
    assertThat(queue.depth()).isEqualTo(2);

    // an older event is not the eligible target of anyone
    InMemorySummaryJobQueue q2 = new InMemorySummaryJobQueue(clock);
    SummaryService s2 = new SummaryService(
        store, diffs, model, new SummaryValidator(), flight, q2, baselines, registry::snapshot, props, metrics, clock);
    s2.onPublished(new GraphPublishEvent(T, new VersionId("v2")));
    assertThat(q2.depth()).isZero();
  }

  @Test
  @DisplayName("S11 FR-35: replay after the summary exists enqueues nothing")
  void s11Replay() {
    baselines.register(T, new VersionId("v1"));
    PairKey q = pair("v1", "v3");
    diffs.register(diff(q));
    GraphPublishEvent event = new GraphPublishEvent(T, new VersionId("v3"));
    service.onPublished(event);
    service.processNext();
    service.onPublished(event);
    assertThat(queue.depth()).isZero();
    assertThat(model.calls()).isEqualTo(1);
    assertThat(store.history(q)).hasSize(1);
  }

  @Test
  @DisplayName("S14 ADR 0010: supersede appends a new record, moves the pointer and keeps history")
  void s14() {
    service.ensure(p, JobReason.ON_DEMAND);
    service.processNext();
    SummaryRecord old = service.find(p).orElseThrow();
    SummaryRecord fresh = ((SummaryOutcome.Ready) service.supersede(p)).summary();
    assertThat(fresh.generationId()).isNotEqualTo(old.generationId());
    assertThat(service.find(p)).contains(fresh);
    assertThat(store.history(p)).extracting(SummaryRecord::generationId)
        .containsExactly(old.generationId(), fresh.generationId());
    assertThat(store.get(p, old.generationId())).contains(old);
  }

  @Test
  @DisplayName("S14 FR-42: a failed supersede returns Failed and leaves the pointer on the old record")
  void s14Failure() {
    service.ensure(p, JobReason.ON_DEMAND);
    service.processNext();
    SummaryRecord old = service.find(p).orElseThrow();
    model.failNext(1);
    assertThat(service.supersede(p)).isInstanceOf(SummaryOutcome.Failed.class);
    assertThat(service.find(p)).contains(old);
    assertThat(store.history(p)).hasSize(1);
  }

  @Test
  @DisplayName("P1 NFR-20: publish to ready with the fake model records the latency on the injected clock")
  void p1() {
    baselines.register(T, new VersionId("v1"));
    PairKey q = pair("v1", "v3");
    diffs.register(diff(q));
    service.onPublished(new GraphPublishEvent(T, new VersionId("v3")));
    clock.advance(Duration.ofSeconds(42));
    while (service.processNext() > 0) {
      // drain
    }
    assertThat(service.find(q)).isPresent();
    assertThat(metrics.readyLatencies()).containsExactly(Duration.ofSeconds(42));
    assertThat(metrics.generatedCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("FR-34: generation counted once even when ensure is asked repeatedly before processing")
  void countsOnce() {
    AtomicInteger n = new AtomicInteger();
    for (int i = 0; i < 5; i++) {
      service.ensure(p, JobReason.ON_DEMAND);
      n.incrementAndGet();
    }
    assertThat(queue.depth()).isEqualTo(1);
    Optional<SummaryRecord> none = service.find(p);
    assertThat(none).isEmpty();
  }
}
