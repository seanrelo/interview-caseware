package com.caseware.fanout.status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.caseware.fanout.adapter.memory.InMemoryEfFirmLookup;
import com.caseware.fanout.adapter.memory.InMemoryFirmFlags;
import com.caseware.fanout.adapter.memory.InMemoryStatusStore;
import com.caseware.fanout.domain.EfId;
import com.caseware.fanout.domain.EfRef;
import com.caseware.fanout.domain.FirmId;
import com.caseware.fanout.domain.Page;
import com.caseware.fanout.domain.TemplateId;
import com.caseware.fanout.domain.VersionId;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PendingUpdateQueryServiceTest {

  private static final Instant T0 = Instant.parse("2026-10-30T10:00:00Z");
  private static final TemplateId T = new TemplateId("T");
  private static final FirmId F = new FirmId("F");
  private static final FirmId G = new FirmId("G");
  private static final Caller CF = new Caller(F, "alice");
  private static final Caller CG = new Caller(G, "gina");

  private final InMemoryStatusStore store = new InMemoryStatusStore();
  private final InMemoryFirmFlags flags = new InMemoryFirmFlags();
  private final InMemoryEfFirmLookup lookup = new InMemoryEfFirmLookup();
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

  private PendingUpdateQueryService query() {
    return new PendingUpdateQueryService(store, flags, lookup, meters);
  }

  private static StatusRow row(FirmId firm, String ef, UpdateStatus st) {
    boolean pending = st == UpdateStatus.UPDATE_PENDING;
    return new StatusRow(
        firm, new EfId(ef), Optional.of(T), st, Optional.of(new VersionId("v1")),
        pending ? Optional.of(new VersionId("v2")) : Optional.empty(), T0, StatusSource.DERIVED);
  }

  private void seed(FirmId firm, String prefix, int n) {
    UpdateStatus[] all = UpdateStatus.values();
    for (int i = 0; i < n; i++) {
      String id = String.format("%s%05d", prefix, i);
      store.put(row(firm, id, all[i % 3]));
      lookup.add(new EfRef(new EfId(id), firm));
    }
  }

  @Test
  @DisplayName("FR-22: list pages rows in EF order, token is stable, totals match the rows")
  void listPagesAndTotals() {
    seed(F, "e", 7);
    PendingUpdateQueryService q = query();
    List<String> seen = new ArrayList<>();
    Optional<String> token = Optional.empty();
    do {
      StatusPage p = q.list(CF, token, 3).orElseThrow();
      p.rows().items().forEach(r -> seen.add(r.ef().value()));
      assertThat(p.totals()).isEqualTo(new StatusCounts(3, 2, 2));
      token = p.rows().nextToken();
    } while (token.isPresent());
    assertThat(seen).containsExactly("e00000", "e00001", "e00002", "e00003", "e00004", "e00005", "e00006");

    // rows written between pages do not shift the token
    StatusPage first = q.list(CF, Optional.empty(), 3).orElseThrow();
    store.put(row(F, "a00000", UpdateStatus.UNKNOWN));
    StatusPage second = q.list(CF, first.rows().nextToken(), 3).orElseThrow();
    assertThat(second.rows().items()).extracting(r -> r.ef().value())
        .containsExactly("e00003", "e00004", "e00005");
    assertThat(second.totals().total()).isEqualTo(8);
  }

  @Test
  @DisplayName("FR-22: detail returns the row")
  void detail() {
    seed(F, "e", 3);
    assertThat(query().detail(CF, new EfId("e00001"))).map(StatusRow::status).contains(UpdateStatus.UPDATE_PENDING);
    assertThat(query().detail(CF, new EfId("missing"))).isEmpty();
  }

  @Test
  @DisplayName("U-33 NFR-33: a firm sees only its own rows and a foreign EF detail is empty")
  void firmIsolation() {
    seed(F, "f", 4);
    seed(G, "g", 2);
    PendingUpdateQueryService q = query();
    assertThat(q.list(CF, Optional.empty(), 100).orElseThrow().rows().items())
        .allMatch(r -> r.firm().equals(F)).hasSize(4);
    assertThat(q.list(CG, Optional.empty(), 100).orElseThrow().totals().total()).isEqualTo(2);
    assertThat(q.detail(CF, new EfId("g00000"))).isEmpty();
    assertThat(q.detail(CG, new EfId("f00000"))).isEmpty();
    // even if the lookup is missing the partition protects
    assertThat(new PendingUpdateQueryService(store, flags, new InMemoryEfFirmLookup())
            .detail(CF, new EfId("g00000"))).isEmpty();
  }

  @Test
  @DisplayName("NFR-33: detail checks the authoritative lookup, not just the partition")
  void detailChecksLookup() {
    EfRef owned = new EfRef(new EfId("x1"), G);
    lookup.add(owned);
    store.put(row(F, "x1", UpdateStatus.UPDATE_PENDING)); // inconsistent data in F's partition
    assertThat(query().detail(CF, owned.ef())).isEmpty();
  }

  @Test
  @DisplayName("U-41 NFR-41: flag off returns empty for list and detail, and reads nothing")
  void flagOff() {
    AtomicLong touched = new AtomicLong();
    InMemoryStatusStore counting = new InMemoryStatusStore() {
      @Override
      public Page<StatusRow> page(FirmId f, Optional<String> t, int s) {
        touched.incrementAndGet();
        return super.page(f, t, s);
      }

      @Override
      public Optional<StatusRow> find(FirmId f, EfId e) {
        touched.incrementAndGet();
        return super.find(f, e);
      }

      @Override
      public StatusCounts counts(FirmId f) {
        touched.incrementAndGet();
        return super.counts(f);
      }
    };
    counting.put(row(F, "e1", UpdateStatus.UPDATE_PENDING));
    flags.setEnabled(F, false);
    PendingUpdateQueryService q = new PendingUpdateQueryService(counting, flags, lookup);
    assertThat(q.list(CF, Optional.empty(), 10)).isEmpty();
    assertThat(q.detail(CF, new EfId("e1"))).isEmpty();
    assertThat(touched).hasValue(0);
  }

  @Test
  @DisplayName("FR-25 NFR-38: a store failure raises StatusUnavailableException, never a page, and is counted")
  void storeFailure() {
    AtomicBoolean fail = new AtomicBoolean(true);
    InMemoryStatusStore flaky = new InMemoryStatusStore() {
      @Override
      public Page<StatusRow> page(FirmId f, Optional<String> t, int s) {
        if (fail.get()) {
          throw new IllegalStateException("timeout");
        }
        return super.page(f, t, s);
      }

      @Override
      public Optional<StatusRow> find(FirmId f, EfId e) {
        throw new IllegalStateException("timeout");
      }
    };
    flaky.put(row(F, "e1", UpdateStatus.UP_TO_DATE));
    PendingUpdateQueryService q = new PendingUpdateQueryService(flaky, flags, lookup, meters);
    assertThatThrownBy(() -> q.list(CF, Optional.empty(), 10)).isInstanceOf(StatusUnavailableException.class);
    assertThatThrownBy(() -> q.detail(CF, new EfId("e1"))).isInstanceOf(StatusUnavailableException.class);
    assertThat(meters.counter("fanout.status.query.errors", "op", "list").count()).isEqualTo(1);
    assertThat(meters.counter("fanout.status.query.errors", "op", "detail").count()).isEqualTo(1);
    assertThat(meters.timer("fanout.status.query", "op", "list", "outcome", "error").count()).isEqualTo(1);

    fail.set(false);
    assertThat(q.list(CF, Optional.empty(), 10)).isPresent();
    assertThat(meters.timer("fanout.status.query", "op", "list", "outcome", "ok").count()).isEqualTo(1);
  }

  @Test
  @DisplayName("P-13 NFR-13 indicative: a row written through the decorator is visible to the query")
  void p13RowVisibleAfterPublish() {
    var reg = new com.caseware.fanout.adapter.memory.InMemoryVersionRegistry();
    reg.register(com.caseware.fanout.graph.GraphFixtures.pub("v1", 1));
    reg.register(com.caseware.fanout.graph.GraphFixtures.pub("v2", 2, "v1"));
    var records = new com.caseware.fanout.adapter.memory.InMemoryTemplateVersionRecordStore();
    EfRef ef = new EfRef(new EfId("e1"), F);
    lookup.add(ef);
    records.upsertVersion(com.caseware.fanout.domain.Region.EU, ef, com.caseware.fanout.graph.GraphFixtures.T,
        com.caseware.fanout.graph.GraphFixtures.v("v1"), T0, com.caseware.fanout.capture.CaptureSource.CREATE);
    var service = new StatusService(records, records, reg::snapshot,
        new com.caseware.fanout.adapter.memory.InMemoryDecisionReader(), store, flags, lookup,
        java.time.Clock.systemUTC());
    var marker = new com.caseware.fanout.adapter.memory.InMemoryPendingUpdateMarker(
        new com.caseware.fanout.adapter.memory.GraphBackedVersionGraphReader(reg));
    var decorated = new StatusRefreshingMarker(marker, service, com.caseware.fanout.domain.Region.EU);

    long start = System.nanoTime();
    decorated.record(new com.caseware.fanout.domain.WorkKey(ef.ef(),
        com.caseware.fanout.graph.GraphFixtures.T, com.caseware.fanout.graph.GraphFixtures.v("v2")));
    Optional<StatusRow> seen = query().detail(CF, ef.ef());
    long micros = (System.nanoTime() - start) / 1_000;
    System.out.println("P-13 (indicative, in-memory): record to visible " + micros + " us; target 10 s p95 (to agree)");
    assertThat(seen).map(StatusRow::status).contains(UpdateStatus.UPDATE_PENDING);
  }

  /** Counts rows touched by page and calls to counts. */
  private static final class CountingStore extends InMemoryStatusStore {
    final AtomicLong rowsTouched = new AtomicLong();
    final AtomicLong countsCalls = new AtomicLong();

    @Override
    public Page<StatusRow> page(FirmId f, Optional<String> t, int s) {
      Page<StatusRow> p = super.page(f, t, s);
      rowsTouched.addAndGet(p.items().size());
      return p;
    }

    @Override
    public StatusCounts counts(FirmId f) {
      countsCalls.incrementAndGet();
      return super.counts(f);
    }
  }

  @Test
  @DisplayName("P-14 NFR-14 NFR-23 indicative: first page and totals of 40,000 rows without scanning all rows")
  void p14LargeFirm() {
    CountingStore counting = new CountingStore();
    UpdateStatus[] all = UpdateStatus.values();
    for (int i = 0; i < 40_000; i++) {
      counting.put(row(F, String.format("e%05d", i), all[i % 3]));
    }
    PendingUpdateQueryService q = new PendingUpdateQueryService(counting, flags, lookup);
    long start = System.nanoTime();
    StatusPage p = q.list(CF, Optional.empty(), 50).orElseThrow();
    long micros = (System.nanoTime() - start) / 1_000;
    System.out.println("P-14 (indicative, in-memory): first page + totals of 40,000 rows " + micros + " us; target 1 s p95 (to agree)");
    assertThat(p.rows().items()).hasSize(50);
    assertThat(p.rows().nextToken()).isPresent();
    assertThat(p.totals().total()).isEqualTo(40_000);
    assertThat(counting.rowsTouched).hasValue(50);
    assertThat(counting.countsCalls).hasValue(1);
  }

  @Test
  @DisplayName("P-24 NFR-24: reads while a writer thread runs throw nothing and end with consistent counts")
  void p24ReadsWhileWriting() throws Exception {
    seed(F, "e", 300);
    AtomicBoolean stop = new AtomicBoolean();
    AtomicBoolean failed = new AtomicBoolean();
    Thread writer = new Thread(() -> {
      UpdateStatus[] all = UpdateStatus.values();
      for (int i = 0; !stop.get() && i < 200_000; i++) {
        store.put(row(F, String.format("e%05d", i % 500), all[(i / 7) % 3]));
      }
    });
    writer.start();
    PendingUpdateQueryService q = query();
    try {
      for (int i = 0; i < 2_000; i++) {
        StatusPage p = q.list(CF, Optional.empty(), 25).orElseThrow();
        if (p.rows().items().size() != 25) {
          failed.set(true);
        }
        q.detail(CF, new EfId("e00010"));
      }
    } finally {
      stop.set(true);
      writer.join();
    }
    assertThat(failed).isFalse();
    StatusPage all = q.list(CF, Optional.empty(), 1_000).orElseThrow();
    StatusCounts c = all.totals();
    assertThat(c.total()).isEqualTo(all.rows().items().size());
    assertThat(all.rows().items().stream().filter(r -> r.status() == UpdateStatus.UPDATE_PENDING).count())
        .isEqualTo(c.updatePending());
    assertThat(all.rows().items().stream().filter(r -> r.status() == UpdateStatus.UNKNOWN).count())
        .isEqualTo(c.unknown());
  }
}
