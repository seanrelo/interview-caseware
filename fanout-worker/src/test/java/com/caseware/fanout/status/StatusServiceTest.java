package com.caseware.fanout.status;

import static com.caseware.fanout.graph.GraphFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.caseware.fanout.adapter.memory.GraphBackedVersionGraphReader;
import com.caseware.fanout.adapter.memory.InMemoryDecisionReader;
import com.caseware.fanout.adapter.memory.InMemoryEfFirmLookup;
import com.caseware.fanout.adapter.memory.InMemoryFirmFlags;
import com.caseware.fanout.adapter.memory.InMemoryPendingUpdateMarker;
import com.caseware.fanout.adapter.memory.InMemoryStatusStore;
import com.caseware.fanout.adapter.memory.InMemoryTemplateVersionRecordStore;
import com.caseware.fanout.adapter.memory.InMemoryVersionRegistry;
import com.caseware.fanout.app.MutableClock;
import com.caseware.fanout.capture.CaptureSource;
import com.caseware.fanout.capture.TemplateVersionRecord;
import com.caseware.fanout.capture.TemplateVersionRecordScan;
import com.caseware.fanout.capture.TemplateVersionRecordStore;
import com.caseware.fanout.capture.UpsertResult;
import com.caseware.fanout.domain.EfId;
import com.caseware.fanout.domain.EfRef;
import com.caseware.fanout.domain.FirmId;
import com.caseware.fanout.domain.Page;
import com.caseware.fanout.domain.Region;
import com.caseware.fanout.domain.TemplateId;
import com.caseware.fanout.domain.VersionId;
import com.caseware.fanout.domain.WorkKey;
import com.caseware.fanout.graph.EligibleResult;
import com.caseware.fanout.graph.VersionGraph;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StatusServiceTest {

  private static final Region EU = Region.EU;
  private static final Instant T0 = Instant.parse("2026-10-30T10:00:00Z");
  private static final FirmId F = new FirmId("F");
  private static final EfRef EF1 = new EfRef(new EfId("ef1"), F);
  private static final EfRef EF2 = new EfRef(new EfId("ef2"), F);

  private final InMemoryVersionRegistry reg = new InMemoryVersionRegistry();
  private final InMemoryTemplateVersionRecordStore records = new InMemoryTemplateVersionRecordStore();
  private final InMemoryDecisionReader decisions = new InMemoryDecisionReader();
  private final InMemoryStatusStore store = new InMemoryStatusStore();
  private final InMemoryFirmFlags flags = new InMemoryFirmFlags();
  private final InMemoryEfFirmLookup lookup = new InMemoryEfFirmLookup();
  private final MutableClock clock = new MutableClock(T0);
  private final InMemoryPendingUpdateMarker marker =
      new InMemoryPendingUpdateMarker(new GraphBackedVersionGraphReader(reg));

  StatusServiceTest() {
    reg.register(pub("v1", 1));
    reg.register(pub("v2", 2, "v1"));
    for (EfRef e : new EfRef[] {EF1, EF2}) {
      lookup.add(e);
      records.upsertVersion(EU, e, T, v("v1"), T0, CaptureSource.CREATE);
    }
  }

  private StatusService service() {
    return service(records, reg::snapshot, decisions, store);
  }

  private StatusService service(
      TemplateVersionRecordStore recs, VersionGraphProvider g, DecisionReader d, StatusStore s) {
    return new StatusService(recs, records, g, d, s, flags, lookup, clock);
  }

  private WorkKey key(EfRef e, String v) {
    return new WorkKey(e.ef(), T, v(v));
  }

  @Test
  @DisplayName("U-23 FR-23: refresh writes a pending row and counts it")
  void refreshWritesPending() {
    StatusRow row = service().refresh(EU, EF1);
    assertThat(store.find(F, EF1.ef())).contains(row);
    assertThat(row.status()).isEqualTo(UpdateStatus.UPDATE_PENDING);
    assertThat(row.pendingTarget()).contains(v("v2"));
    assertThat(row.baseline()).contains(v("v1"));
    assertThat(row.updatedAt()).isEqualTo(T0);
    assertThat(store.counts(F)).isEqualTo(new StatusCounts(0, 1, 0));
  }

  @Test
  @DisplayName("FR-16 NFR-28: decorator record produces a pending row after the delegate wrote")
  void decoratorRecord() {
    StatusRefreshingMarker m = new StatusRefreshingMarker(marker, service(), EU);
    m.record(key(EF1, "v2"));
    assertThat(marker.status(EF1.ef(), T)).isPresent();
    StatusRow row = store.find(F, EF1.ef()).orElseThrow();
    assertThat(row.status()).isEqualTo(UpdateStatus.UPDATE_PENDING);
    assertThat(row.pendingTarget()).contains(v("v2"));
    assertThat(row.source()).isEqualTo(StatusSource.DERIVED);
  }

  @Test
  @DisplayName("FR-13 NFR-28: after reevaluate the row agrees with what the marker wrote")
  void reevaluateAgrees() {
    reg.register(pub("v3", 3, "v2"));
    StatusRefreshingMarker m = new StatusRefreshingMarker(marker, service(), EU);
    m.record(key(EF1, "v3"));
    assertThat(store.find(F, EF1.ef()).orElseThrow().pendingTarget()).contains(v("v3"));

    reg.withdraw(T, v("v3"));
    m.reevaluate(key(EF1, "v3"), reg.snapshot().eligibleVersion(T, v("v1")));
    assertThat(marker.status(EF1.ef(), T).orElseThrow().target()).isEqualTo(v("v2"));
    assertThat(store.find(F, EF1.ef()).orElseThrow().pendingTarget()).contains(v("v2"));

    reg.withdraw(T, v("v2"));
    m.reevaluate(key(EF1, "v2"), reg.snapshot().eligibleVersion(T, v("v1")));
    assertThat(marker.status(EF1.ef(), T).orElseThrow().upToDate()).isTrue();
    assertThat(store.find(F, EF1.ef()).orElseThrow().status()).isEqualTo(UpdateStatus.UP_TO_DATE);
  }

  @Test
  @DisplayName("FR-19 FR-25: markUnknown writes UNKNOWN from FANOUT_UNKNOWN, a later refresh overwrites")
  void markUnknownThenRefresh() {
    StatusService s = service();
    StatusRefreshingMarker m = new StatusRefreshingMarker(marker, s, EU);
    m.markUnknown(key(EF1, "v2"), "dead-lettered");
    StatusRow row = store.find(F, EF1.ef()).orElseThrow();
    assertThat(row.status()).isEqualTo(UpdateStatus.UNKNOWN);
    assertThat(row.source()).isEqualTo(StatusSource.FANOUT_UNKNOWN);
    assertThat(row.template()).contains(T);

    clock.advance(Duration.ofSeconds(5));
    s.refresh(EU, EF1);
    StatusRow after = store.find(F, EF1.ef()).orElseThrow();
    assertThat(after.status()).isEqualTo(UpdateStatus.UPDATE_PENDING);
    assertThat(after.source()).isEqualTo(StatusSource.DERIVED);
    assertThat(store.counts(F)).isEqualTo(new StatusCounts(0, 1, 0));
  }

  @Test
  @DisplayName("NFR-30: a missing firm lookup does not break the fan-out call; it is counted")
  void lookupFailure() {
    StatusRefreshingMarker unresolved =
        new StatusRefreshingMarker(marker, service(), EU);
    WorkKey ghost = new WorkKey(new EfId("ghost"), T, v("v2"));
    assertThat(unresolved.record(ghost)).isEqualTo(com.caseware.fanout.port.MarkResult.APPLIED);
    unresolved.markUnknown(ghost, "r");
    assertThat(unresolved.unresolvedFirms()).isEqualTo(2);

    EfFirmLookupThrows throwing = new EfFirmLookupThrows();
    StatusService s =
        new StatusService(records, records, reg::snapshot, decisions, store, flags, throwing, clock);
    StatusRefreshingMarker m = new StatusRefreshingMarker(marker, s, EU);
    assertThat(m.record(key(EF1, "v2"))).isEqualTo(com.caseware.fanout.port.MarkResult.APPLIED);
    assertThat(m.refreshFailures()).isEqualTo(1);
    assertThat(store.find(F, EF1.ef())).isEmpty();
  }

  private static final class EfFirmLookupThrows implements EfFirmLookup {
    public Optional<EfRef> firmOf(EfId ef) {
      throw new IllegalStateException("lookup down");
    }
  }

  @Test
  @DisplayName("NFR-12 NFR-28: a stale refresh cannot overwrite a fresher one")
  void staleRefreshDoesNotClobber() throws Exception {
    CountDownLatch inside = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicBoolean first = new AtomicBoolean(true);
    // The first snapshot is taken before v3 exists and is held until the second refresh waits.
    VersionGraphProvider gated =
        () -> {
          VersionGraph g = reg.snapshot();
          if (first.getAndSet(false)) {
            inside.countDown();
            try {
              release.await();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
          return g;
        };
    // Version graph where EF1 is already at v2, so the stale view says UP_TO_DATE.
    records.upsertBaseline(EU, EF1, T, v("v2"), T0.plusSeconds(1));
    StatusService s = service(records, gated, decisions, store);

    AtomicReference<Throwable> err = new AtomicReference<>();
    Thread a = new Thread(() -> run(() -> s.refresh(EU, EF1), err));
    a.start();
    inside.await();
    reg.register(pub("v3", 3, "v2"));
    Thread b = new Thread(() -> run(() -> s.refresh(EU, EF1), err));
    b.start();
    while (b.getState() == Thread.State.RUNNABLE || b.getState() == Thread.State.NEW) {
      Thread.onSpinWait();
    }
    assertThat(b.getState()).isIn(Thread.State.WAITING, Thread.State.TIMED_WAITING, Thread.State.BLOCKED);
    release.countDown();
    a.join();
    b.join();
    assertThat(err.get()).isNull();
    StatusRow row = store.find(F, EF1.ef()).orElseThrow();
    assertThat(row.status()).isEqualTo(UpdateStatus.UPDATE_PENDING);
    assertThat(row.pendingTarget()).contains(v("v3"));
  }

  private static void run(Runnable r, AtomicReference<Throwable> err) {
    try {
      r.run();
    } catch (Throwable t) {
      err.set(t);
    }
  }

  @Test
  @DisplayName("NFR-28: refreshFirm repairs a missing row and ignores other firms")
  void refreshFirmRepairs() {
    EfRef other = new EfRef(new EfId("ef9"), new FirmId("G"));
    records.upsertVersion(EU, other, T, v("v1"), T0, CaptureSource.CREATE);
    StatusService s = service();
    s.refresh(EU, EF1);
    assertThat(store.find(F, EF2.ef())).isEmpty();
    assertThat(s.refreshFirm(EU, F)).isEqualTo(2);
    assertThat(store.find(F, EF2.ef())).isPresent();
    assertThat(store.counts(F)).isEqualTo(new StatusCounts(0, 2, 0));
    assertThat(store.find(new FirmId("G"), other.ef())).isEmpty();
  }

  @Test
  @DisplayName("FR-25: failure injection on records, graph, decisions and store never yields UP_TO_DATE")
  void failureInjection() {
    // Make the true answer UP_TO_DATE so that a wrong fallback would show.
    decisions.decline(EF1.ef(), T, v("v2"));
    assertThat(service().refresh(EU, EF1).status()).isEqualTo(UpdateStatus.UP_TO_DATE);

    TemplateVersionRecordStore badRecords =
        new TemplateVersionRecordStore() {
          public UpsertResult upsertVersion(Region r, EfRef e, TemplateId t, VersionId v, Instant i, CaptureSource s) {
            throw new IllegalStateException();
          }

          public UpsertResult upsertBaseline(Region r, EfRef e, TemplateId t, VersionId v, Instant i) {
            throw new IllegalStateException();
          }

          public Optional<TemplateVersionRecord> find(Region r, EfRef e) {
            throw new IllegalStateException("records down");
          }
        };
    VersionGraphProvider badGraph =
        () -> {
          throw new IllegalStateException("graph down");
        };
    DecisionReader badDecisions =
        new DecisionReader() {
          public Set<VersionId> declined(EfId e, TemplateId t) {
            throw new IllegalStateException("decisions down");
          }

          public Set<VersionId> accepted(EfId e, TemplateId t) {
            throw new IllegalStateException("decisions down");
          }
        };
    for (StatusService s :
        new StatusService[] {
          service(badRecords, reg::snapshot, decisions, store),
          service(records, badGraph, decisions, store),
          service(records, reg::snapshot, badDecisions, store)
        }) {
      StatusRow row = s.refresh(EU, EF1);
      assertThat(row.status()).isEqualTo(UpdateStatus.UNKNOWN);
      assertThat(store.find(F, EF1.ef()).orElseThrow().status()).isEqualTo(UpdateStatus.UNKNOWN);
    }
    assertThat(store.counts(F)).isEqualTo(new StatusCounts(0, 0, 1));

    // Store that rejects the first put: the fallback UNKNOWN row is written instead.
    AtomicBoolean fail = new AtomicBoolean(true);
    InMemoryStatusStore flaky =
        new InMemoryStatusStore() {
          @Override
          public void put(StatusRow row) {
            if (fail.getAndSet(false)) {
              throw new IllegalStateException("store timeout");
            }
            super.put(row);
          }
        };
    StatusRow r = service(records, reg::snapshot, decisions, flaky).refresh(EU, EF2);
    assertThat(r.status()).isEqualTo(UpdateStatus.UNKNOWN);
    assertThat(flaky.find(F, EF2.ef()).orElseThrow().status()).isEqualTo(UpdateStatus.UNKNOWN);

    // Store that always fails: nothing is written, the failure surfaces, no UP_TO_DATE row exists.
    InMemoryStatusStore dead =
        new InMemoryStatusStore() {
          @Override
          public void put(StatusRow row) {
            throw new IllegalStateException("store down");
          }
        };
    assertThatThrownBy(() -> service(records, reg::snapshot, decisions, dead).refresh(EU, EF2))
        .isInstanceOf(IllegalStateException.class);
    assertThat(dead.find(F, EF2.ef())).isEmpty();
  }

  @Test
  @DisplayName("NFR-41: refresh writes nothing for a flag-off firm; refreshFirm is a no-op")
  void flagOff() {
    flags.setEnabled(F, false);
    StatusService s = service();
    StatusRefreshingMarker m = new StatusRefreshingMarker(marker, s, EU);
    s.refresh(EU, EF1);
    m.record(key(EF1, "v2"));
    m.markUnknown(key(EF2, "v2"), "r");
    assertThat(s.refreshFirm(EU, F)).isZero();
    assertThat(store.find(F, EF1.ef())).isEmpty();
    assertThat(store.find(F, EF2.ef())).isEmpty();
    assertThat(store.counts(F)).isEqualTo(new StatusCounts(0, 0, 0));
    flags.setEnabled(F, true);
    assertThat(s.refreshFirm(EU, F)).isEqualTo(2);
    assertThat(store.counts(F).updatePending()).isEqualTo(2);
  }

  @Test
  @DisplayName("FR-28 FR-32: decisions are read on each refresh (decline then accept)")
  void decisionsApplied() {
    StatusService s = service();
    assertThat(s.refresh(EU, EF1).status()).isEqualTo(UpdateStatus.UPDATE_PENDING);
    decisions.decline(EF1.ef(), T, v("v2"));
    assertThat(s.refresh(EU, EF1).status()).isEqualTo(UpdateStatus.UP_TO_DATE);
    reg.register(pub("v3", 3, "v2"));
    StatusRow row = s.refresh(EU, EF1);
    assertThat(row.pendingTarget()).contains(v("v3"));
    assertThat(row.baseline()).contains(v("v1"));
    decisions.accept(EF1.ef(), T, v("v3"));
    assertThat(s.refresh(EU, EF1).status()).isEqualTo(UpdateStatus.UP_TO_DATE);
  }

  @Test
  @DisplayName("NFR-14: store pages in EF id order with last-id token and keeps counts")
  void storePaging() {
    InMemoryStatusStore st = new InMemoryStatusStore();
    for (int i = 0; i < 5; i++) {
      st.put(
          new StatusRow(F, new EfId("e" + i), Optional.empty(), UpdateStatus.UNKNOWN,
              Optional.empty(), Optional.empty(), T0, StatusSource.DERIVED));
    }
    Page<StatusRow> p1 = st.page(F, Optional.empty(), 2);
    assertThat(p1.items()).extracting(r -> r.ef().value()).containsExactly("e0", "e1");
    assertThat(p1.nextToken()).contains("e1");
    Page<StatusRow> p3 = st.page(F, Optional.of("e3"), 2);
    assertThat(p3.items()).extracting(r -> r.ef().value()).containsExactly("e4");
    assertThat(p3.nextToken()).isEmpty();
    assertThat(st.counts(F)).isEqualTo(new StatusCounts(0, 0, 5));
    assertThat(st.counts(F).total()).isEqualTo(5);
  }
}
