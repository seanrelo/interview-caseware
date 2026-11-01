package com.caseware.fanout.summary;

import static com.caseware.fanout.summary.SummaryFixtures.MODEL;
import static com.caseware.fanout.summary.SummaryFixtures.PROMPT;
import static com.caseware.fanout.summary.SummaryFixtures.diff;
import static com.caseware.fanout.summary.SummaryFixtures.pair;
import static com.caseware.fanout.summary.SummaryFixtures.record;
import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.fanout.adapter.memory.FakeModelClient;
import com.caseware.fanout.adapter.memory.InMemoryBaselinesInUse;
import com.caseware.fanout.adapter.memory.InMemoryDiffSource;
import com.caseware.fanout.adapter.memory.InMemoryFirmFlags;
import com.caseware.fanout.adapter.memory.InMemorySingleFlight;
import com.caseware.fanout.adapter.memory.InMemorySummaryJobQueue;
import com.caseware.fanout.adapter.memory.InMemorySummaryMetrics;
import com.caseware.fanout.adapter.memory.InMemorySummaryStore;
import com.caseware.fanout.app.MutableClock;
import com.caseware.fanout.domain.EfId;
import com.caseware.fanout.domain.FirmId;
import com.caseware.fanout.domain.TemplateId;
import com.caseware.fanout.graph.ImmutableVersionGraph;
import com.caseware.fanout.status.Caller;
import com.caseware.fanout.status.PendingUpdateQuery;
import com.caseware.fanout.status.StatusPage;
import com.caseware.fanout.status.StatusRow;
import com.caseware.fanout.status.StatusSource;
import com.caseware.fanout.status.UpdateStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SummaryQueryTest {
  static final TemplateId T = new TemplateId("T");
  final FirmId firmA = new FirmId("A");
  final FirmId firmB = new FirmId("B");
  final Caller alice = new Caller(firmA, "alice");
  final Caller bob = new Caller(firmB, "bob");
  final Caller mallory = new Caller(new FirmId("C"), "mallory");
  final MutableClock clock = new MutableClock(Instant.parse("2026-11-01T10:00:00Z"));
  final InMemorySummaryStore store = new InMemorySummaryStore();
  final InMemoryDiffSource diffs = new InMemoryDiffSource();
  final FakeModelClient model = new FakeModelClient();
  final InMemorySummaryJobQueue queue = new InMemorySummaryJobQueue(clock);
  final InMemorySummaryMetrics metrics = new InMemorySummaryMetrics();
  final InMemoryFirmFlags flags = new InMemoryFirmFlags();
  final Map<String, StatusRow> rows = new HashMap<>();
  final PairKey p = pair("v1", "v2");
  SummaryService service;
  SummaryQuery query;

  /** Status read fake keyed by firm and EF, so a foreign firm gets empty exactly like the real query. */
  final PendingUpdateQuery status = new PendingUpdateQuery() {
    @Override
    public Optional<StatusPage> list(Caller caller, Optional<String> token, int size) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<StatusRow> detail(Caller caller, EfId ef) {
      return Optional.ofNullable(rows.get(caller.firm().value() + "/" + ef.value()));
    }
  };

  @BeforeEach
  void setUp() {
    SummaryProperties props = new SummaryProperties(MODEL, PROMPT, 3, Duration.ofMinutes(2),
        Duration.ofSeconds(10), Duration.ofSeconds(25), 0.01, 0.05);
    service = new SummaryService(store, diffs, model, new SummaryValidator(), new InMemorySingleFlight(clock),
        queue, new InMemoryBaselinesInUse(), () -> new ImmutableVersionGraph(List.of()), props, metrics, clock);
    query = new SummaryQuery(status, flags, service);
    diffs.register(diff(p));
    put(firmA, "e1", UpdateStatus.UPDATE_PENDING);
    put(firmB, "e2", UpdateStatus.UPDATE_PENDING);
  }

  void put(FirmId firm, String ef, UpdateStatus st) {
    boolean pending = st == UpdateStatus.UPDATE_PENDING;
    rows.put(firm.value() + "/" + ef, new StatusRow(firm, new EfId(ef), Optional.of(T), st,
        Optional.of(p.baseline()), pending ? Optional.of(p.target()) : Optional.empty(),
        clock.instant(), StatusSource.DERIVED));
  }

  @Test
  @DisplayName("FR-34, FR-43: generating first, then Ready labelled AI-generated")
  void generatingThenReady() {
    assertThat(query.summaryFor(alice, new EfId("e1"))).containsInstanceOf(SummaryView.Generating.class);
    service.processNext();
    SummaryView.Ready ready = (SummaryView.Ready) query.summaryFor(alice, new EfId("e1")).orElseThrow();
    assertThat(ready.label()).isEqualTo("AI-generated");
    assertThat(ready.summary().aiGenerated()).isTrue();
  }

  @Test
  @DisplayName("S14 FR-36: two firms see the same generationId")
  void sharedAcrossFirms() {
    query.summaryFor(alice, new EfId("e1"));
    service.processNext();
    var a = (SummaryView.Ready) query.summaryFor(alice, new EfId("e1")).orElseThrow();
    var b = (SummaryView.Ready) query.summaryFor(bob, new EfId("e2")).orElseThrow();
    assertThat(a.summary().generationId()).isEqualTo(b.summary().generationId());
    assertThat(model.calls()).isEqualTo(1);
  }

  @Test
  @DisplayName("S5 NFR-41: flag off gives empty, no job and an unchanged store")
  void s5() {
    flags.setEnabled(firmA, false);
    assertThat(query.summaryFor(alice, new EfId("e1"))).isEmpty();
    assertThat(queue.depth()).isZero();
    assertThat(store.history(p)).isEmpty();
    assertThat(model.calls()).isZero();
  }

  @Test
  @DisplayName("NFR-33: a foreign firm gets empty for another firm's EF and creates no job")
  void foreign() {
    assertThat(query.summaryFor(mallory, new EfId("e1"))).isEmpty();
    assertThat(query.summaryFor(bob, new EfId("e1"))).isEmpty();
    assertThat(queue.depth()).isZero();
  }

  @Test
  @DisplayName("FR-33: an EF that is not UPDATE_PENDING has no summary view")
  void notPending() {
    put(firmA, "e3", UpdateStatus.UP_TO_DATE);
    put(firmA, "e4", UpdateStatus.UNKNOWN);
    assertThat(query.summaryFor(alice, new EfId("e3"))).isEmpty();
    assertThat(query.summaryFor(alice, new EfId("e4"))).isEmpty();
    assertThat(queue.depth()).isZero();
  }

  @Test
  @DisplayName("S4 FR-42, NFR-30: generation failure shows Unavailable while the status stays pending")
  void failureUnavailable() {
    model.failNext(10);
    assertThat(query.summaryFor(alice, new EfId("e1"))).containsInstanceOf(SummaryView.Generating.class);
    for (int i = 0; i < 3; i++) {
      service.processNext();
      clock.advance(Duration.ofSeconds(30));
    }
    assertThat(queue.deadLetters()).hasSize(1);
    assertThat(query.summaryFor(alice, new EfId("e1"))).containsInstanceOf(SummaryView.Unavailable.class);
    assertThat(queue.depth()).isZero();
    assertThat(store.history(p)).isEmpty();
    assertThat(status.detail(alice, new EfId("e1"))).map(StatusRow::status).contains(UpdateStatus.UPDATE_PENDING);
  }

  @Test
  @DisplayName("S9 FR-42, NFR-15: a tampered record gives Unavailable and the status is untouched")
  void tamper() {
    query.summaryFor(alice, new EfId("e1"));
    service.processNext();
    SummaryRecord good = store.current(p).orElseThrow();
    store.tamperForTest(p, good.generationId(), SummaryRecord.create(good.generationId(), p,
        List.of(new Statement("Forged.", List.of("d1"))), diff(p), MODEL, PROMPT, good.generatedAt()));
    SummaryView view = query.summaryFor(alice, new EfId("e1")).orElseThrow();
    assertThat(view).isInstanceOf(SummaryView.Unavailable.class);
    assertThat(((SummaryView.Unavailable) view).message()).isEqualTo("summary unavailable");
    assertThat(status.detail(alice, new EfId("e1"))).map(StatusRow::status).contains(UpdateStatus.UPDATE_PENDING);
  }

  @Test
  @DisplayName("FR-42: an unreadable store gives Unavailable, never a summary")
  void storeDown() {
    InMemorySummaryStore down = new InMemorySummaryStore() {
      @Override
      public Optional<SummaryRecord> current(PairKey pair) {
        throw new IllegalStateException("down");
      }
    };
    SummaryProperties props = new SummaryProperties(MODEL, PROMPT);
    SummaryService s = new SummaryService(down, diffs, model, new SummaryValidator(),
        new InMemorySingleFlight(clock), queue, new InMemoryBaselinesInUse(),
        () -> new ImmutableVersionGraph(List.of()), props, metrics, clock);
    assertThat(new SummaryQuery(status, flags, s).summaryFor(alice, new EfId("e1")))
        .containsInstanceOf(SummaryView.Unavailable.class);
  }
}
