package com.caseware.fanout.graph;

import static com.caseware.fanout.graph.GraphFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.caseware.fanout.adapter.memory.GraphBackedVersionGraphReader;
import com.caseware.fanout.adapter.memory.InMemoryBaselineLookup;
import com.caseware.fanout.adapter.memory.InMemoryFirmFlags;
import com.caseware.fanout.adapter.memory.InMemoryPendingTargetIndex;
import com.caseware.fanout.adapter.memory.InMemoryPendingUpdateMarker;
import com.caseware.fanout.adapter.memory.InMemoryPendingUpdateMarker.Status;
import com.caseware.fanout.adapter.memory.InMemoryVersionRegistry;
import com.caseware.fanout.domain.EfId;
import com.caseware.fanout.domain.EfRef;
import com.caseware.fanout.domain.FirmId;
import com.caseware.fanout.domain.Page;
import com.caseware.fanout.domain.Region;
import com.caseware.fanout.domain.TemplateId;
import com.caseware.fanout.domain.VersionId;
import com.caseware.fanout.domain.WorkKey;
import com.caseware.fanout.port.FirmFlags;
import com.caseware.fanout.port.PendingUpdateMarker;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WithdrawalReevaluatorTest {

  private static final Region EU = Region.EU;
  private static final FirmId F = new FirmId("F");
  private static final FirmId G = new FirmId("G");

  private final InMemoryVersionRegistry reg = new InMemoryVersionRegistry();
  private final InMemoryPendingTargetIndex index = new InMemoryPendingTargetIndex();
  private final InMemoryBaselineLookup baselines = new InMemoryBaselineLookup();
  private final InMemoryFirmFlags flags = new InMemoryFirmFlags();
  private final InMemoryPendingUpdateMarker marker =
      new InMemoryPendingUpdateMarker(new GraphBackedVersionGraphReader(reg));

  private WithdrawalReevaluator evaluator(PendingUpdateMarker m, BaselineLookup b, FirmFlags f, int page) {
    return new WithdrawalReevaluator(reg, index, b, m, f, page);
  }

  private WithdrawalReevaluator evaluator() {
    return evaluator(marker, baselines, flags, 2);
  }

  private void register(TemplateVersion... vs) {
    for (TemplateVersion v : vs) {
      reg.register(v);
    }
  }

  private static EfRef ef(String id, FirmId firm) {
    return new EfRef(new EfId(id), firm);
  }

  /** Seeds an EF pending on {@code target} with the given baseline, in the status store and index. */
  private void pend(Region r, TemplateId t, EfRef ef, String baseline, String target) {
    baselines.set(r, ef, t, v(baseline));
    marker.record(new WorkKey(ef.ef(), t, v(target)));
    index.record(r, t, v(target), ef);
  }

  private Optional<Status> status(EfRef ef) {
    return marker.status(ef.ef(), T);
  }

  private List<EfRef> allAffected(Region r, TemplateId t, String target, int page) {
    List<EfRef> out = new ArrayList<>();
    Optional<String> token = Optional.empty();
    do {
      Page<EfRef> p = index.affectedBy(r, t, v(target), token, page);
      out.addAll(p.items());
      token = p.nextToken();
    } while (token.isPresent());
    return out;
  }

  /** v1 - v2 - v3 - v4, all main. */
  private void chain() {
    register(pub("v1", 1), pub("v2", 2, "v1"), pub("v3", 3, "v2"), pub("v4", 4, "v3"));
  }

  @Test
  @DisplayName("TG-10 FR-13 NFR-35: affected set is exact and paged; other target, template, region excluded")
  void affectedSetExact() {
    chain();
    TemplateId other = new TemplateId("U");
    EfRef a1 = ef("a1", F), a2 = ef("a2", F), a3 = ef("a3", G), b1 = ef("b1", F), c1 = ef("c1", F), e1 = ef("e1", F);
    index.record(EU, T, v("v3"), a1);
    index.record(EU, T, v("v3"), a2);
    index.record(EU, T, v("v3"), a3);
    index.record(EU, T, v("v2"), b1);
    index.record(EU, other, v("v3"), c1);
    index.record(Region.CA, T, v("v3"), e1);

    assertThat(allAffected(EU, T, "v3", 2)).containsExactly(a1, a2, a3);
    assertThat(allAffected(EU, T, "v3", 100)).containsExactly(a1, a2, a3);
    assertThat(allAffected(Region.CA, T, "v3", 2)).containsExactly(e1);
    index.remove(EU, T, v("v2"), a1); // wrong target: no effect
    assertThat(allAffected(EU, T, "v3", 5)).contains(a1);
    index.remove(EU, T, v("v3"), a1);
    assertThat(allAffected(EU, T, "v3", 5)).containsExactly(a2, a3);
  }

  @Test
  @DisplayName("TG-10a FR-13: no entries gives empty set, no error, no status write")
  void emptyAffected() {
    chain();
    reg.withdraw(T, v("v3"));
    ReevaluationReport r = evaluator().onWithdrawn(EU, T, v("v3"));
    assertThat(r).isEqualTo(new ReevaluationReport(0, 0, 0, 0, 0, List.of()));
    assertThat(marker.snapshot(T)).isEmpty();
    assertThat(marker.totalRecordCalls()).isZero();
  }

  @Test
  @DisplayName("TG-10b NFR-35: withdrawal in EU never touches CA entries")
  void regionIsolation() {
    chain();
    EfRef eu = ef("eu1", F), ca = ef("ca1", F);
    pend(EU, T, eu, "v1", "v3");
    pend(Region.CA, T, ca, "v1", "v3");
    reg.withdraw(T, v("v3"));
    ReevaluationReport r = evaluator().onWithdrawn(EU, T, v("v3"));
    assertThat(r.evaluated()).isEqualTo(1);
    assertThat(allAffected(Region.CA, T, "v3", 5)).containsExactly(ca);
    assertThat(allAffected(Region.CA, T, "v4", 5)).isEmpty();
    assertThat(allAffected(EU, T, "v4", 5)).containsExactly(eu);
  }

  @Test
  @DisplayName("TG-11 FR-13 FR-11: EF pending on withdrawn v3 moves to next eligible v4, index follows")
  void movesToNextEligible() {
    chain();
    EfRef a1 = ef("a1", F), a2 = ef("a2", F), a3 = ef("a3", F);
    pend(EU, T, a1, "v1", "v3");
    pend(EU, T, a2, "v2", "v3");
    pend(EU, T, a3, "v1", "v3");
    reg.withdraw(T, v("v3"));

    ReevaluationReport r = evaluator().onWithdrawn(EU, T, v("v3"));

    assertThat(r).isEqualTo(new ReevaluationReport(3, 3, 0, 0, 0, List.of()));
    for (EfRef e : List.of(a1, a2, a3)) {
      assertThat(status(e)).contains(new Status(v("v4"), false));
    }
    assertThat(allAffected(EU, T, "v3", 5)).isEmpty();
    assertThat(allAffected(EU, T, "v4", 5)).containsExactly(a1, a2, a3);
  }

  @Test
  @DisplayName("TG-11 FR-13 (ADR 0006): eligible older than the withdrawn target lowers pending via reevaluate; none left clears it")
  void eligibleOlderThanWithdrawnAllowed() {
    register(pub("v1", 1), pub("v2", 2, "v1"), pub("v3", 3, "v2"));
    EfRef a1 = ef("a1", F);
    pend(EU, T, a1, "v1", "v3");
    reg.withdraw(T, v("v3"));

    ReevaluationReport r = evaluator().onWithdrawn(EU, T, v("v3"));

    assertThat(r.markedPending()).isEqualTo(1);
    assertThat(r.markedUnknown()).isZero();
    assertThat(status(a1)).contains(new Status(v("v2"), false));
    assertThat(allAffected(EU, T, "v3", 5)).isEmpty();
    assertThat(allAffected(EU, T, "v2", 5)).containsExactly(a1);
  }

  @Test
  @DisplayName("TG-11 FR-13 (ADR 0006): no eligible left clears pending; marker status is up to date")
  void noneLeftClearsMarker() {
    register(pub("v1", 1), pub("v2", 2, "v1"), pub("v3", 3, "v2"));
    EfRef a1 = ef("a1", F);
    pend(EU, T, a1, "v2", "v3");
    reg.withdraw(T, v("v3"));

    evaluator().onWithdrawn(EU, T, v("v3"));

    assertThat(status(a1)).contains(new Status(v("v3"), false, true));
    assertThat(marker.record(new WorkKey(a1.ef(), T, v("v3"))))
        .isEqualTo(com.caseware.fanout.port.MarkResult.UNCHANGED);
    assertThat(status(a1)).contains(new Status(v("v3"), false, true));
  }

  @Test
  @DisplayName("TG-11 FR-13: marker throws on reevaluate, index entry kept, replay repairs the EF")
  void replayAfterReevaluateFailure() {
    register(pub("v1", 1), pub("v2", 2, "v1"), pub("v3", 3, "v2"));
    EfRef a1 = ef("a1", F);
    pend(EU, T, a1, "v1", "v3");
    reg.withdraw(T, v("v3"));
    java.util.concurrent.atomic.AtomicBoolean down = new java.util.concurrent.atomic.AtomicBoolean(true);
    PendingUpdateMarker flaky =
        new PendingUpdateMarker() {
          public com.caseware.fanout.port.MarkResult record(WorkKey k) { return marker.record(k); }
          public void markUnknown(WorkKey k, String why) { marker.markUnknown(k, why); }
          public com.caseware.fanout.port.MarkResult reevaluate(WorkKey k, EligibleResult r) {
            if (down.get()) { throw new IllegalStateException("store down"); }
            return marker.reevaluate(k, r);
          }
        };

    ReevaluationReport first = evaluator(flaky, baselines, flags, 2).onWithdrawn(EU, T, v("v3"));

    assertThat(first.failed()).hasSize(1);
    assertThat(allAffected(EU, T, "v3", 5)).containsExactly(a1);
    down.set(false);
    ReevaluationReport second = evaluator(flaky, baselines, flags, 2).onWithdrawn(EU, T, v("v3"));
    assertThat(second.failed()).isEmpty();
    assertThat(status(a1)).contains(new Status(v("v2"), false));
    assertThat(allAffected(EU, T, "v3", 5)).isEmpty();
    // A further replay changes nothing.
    evaluator().onWithdrawn(EU, T, v("v3"));
    assertThat(status(a1)).contains(new Status(v("v2"), false));
  }

  @Test
  @DisplayName("TG-11 FR-13 NFR-32: stale index entry (marker target no longer the withdrawn one) is dropped, eligible target not indexed")
  void staleIndexEntryDoesNotIndexEligible() {
    register(pub("v1", 1), pub("v2", 2, "v1"), pub("v3", 3, "v2"), pub("v4", 4, "v3"));
    register(node("v5", 5, CA, VersionStatus.PUBLISHED, "v4"));
    EfRef a1 = ef("a1", F);
    baselines.set(EU, a1, T, v("v1"));
    marker.record(new WorkKey(a1.ef(), T, v("v5"))); // EF already moved on to v5
    index.record(EU, T, v("v3"), a1); // stale entry
    reg.withdraw(T, v("v3"));

    evaluator().onWithdrawn(EU, T, v("v3"));

    assertThat(allAffected(EU, T, "v3", 5)).isEmpty();
    assertThat(allAffected(EU, T, "v4", 5)).isEmpty();
    assertThat(status(a1)).contains(new Status(v("v5"), false));
  }

  @Test
  @DisplayName("TG-11 FR-13: UpToDate outcome drops the index entry and is reported (marker has no clear write)")
  void upToDateDropsIndexEntry() {
    register(pub("v1", 1), pub("v2", 2, "v1"), pub("v3", 3, "v2"));
    EfRef a1 = ef("a1", F);
    pend(EU, T, a1, "v2", "v3");
    reg.withdraw(T, v("v3"));

    ReevaluationReport r = evaluator().onWithdrawn(EU, T, v("v3"));

    assertThat(r).isEqualTo(new ReevaluationReport(1, 0, 1, 0, 0, List.of()));
    assertThat(allAffected(EU, T, "v3", 5)).isEmpty();
  }

  @Test
  @DisplayName("TG-11a FR-13 FR-12: no eligible left and a later unreachable node exists gives UNKNOWN")
  void unknownWhenUnreachableLaterNode() {
    register(pub("v1", 1), pub("v3", 3, "v1"), pub("v5", 5));
    EfRef a1 = ef("a1", F);
    pend(EU, T, a1, "v1", "v3");
    reg.withdraw(T, v("v3"));

    ReevaluationReport r = evaluator().onWithdrawn(EU, T, v("v3"));

    assertThat(r).isEqualTo(new ReevaluationReport(1, 0, 0, 1, 0, List.of()));
    assertThat(status(a1)).contains(new Status(v("v3"), true));
  }

  @Test
  @DisplayName("TG-11a FR-12: missing stored baseline gives UNKNOWN, never UP_TO_DATE")
  void missingBaselineUnknown() {
    chain();
    EfRef a1 = ef("a1", F);
    marker.record(new WorkKey(a1.ef(), T, v("v3")));
    index.record(EU, T, v("v3"), a1);
    reg.withdraw(T, v("v3"));

    ReevaluationReport r = evaluator().onWithdrawn(EU, T, v("v3"));

    assertThat(r.markedUnknown()).isEqualTo(1);
    assertThat(r.upToDate()).isZero();
    assertThat(status(a1)).contains(new Status(v("v3"), true));
  }

  @Test
  @DisplayName("TG-11b FR-13: failure for one EF among three ends unknown, others updated, failure surfaced")
  void perEfFailureEndsUnknownAndLoopContinues() {
    chain();
    EfRef a1 = ef("a1", F), a2 = ef("a2", F), a3 = ef("a3", F);
    pend(EU, T, a1, "v1", "v3");
    pend(EU, T, a2, "v1", "v3");
    pend(EU, T, a3, "v1", "v3");
    reg.withdraw(T, v("v3"));
    BaselineLookup flaky =
        (region, e, t) -> {
          if (e.equals(a2)) {
            throw new IllegalStateException("graph read failed");
          }
          return baselines.baselineOf(region, e, t);
        };

    ReevaluationReport r = evaluator(marker, flaky, flags, 2).onWithdrawn(EU, T, v("v3"));

    assertThat(r.failed()).hasSize(1);
    assertThat(r.failed().get(0).ef()).isEqualTo(a2);
    assertThat(r.markedPending()).isEqualTo(2);
    assertThat(r.markedUnknown()).isEqualTo(1);
    assertThat(r.upToDate()).isZero();
    assertThat(status(a1)).contains(new Status(v("v4"), false));
    assertThat(status(a2)).contains(new Status(v("v3"), true));
    assertThat(status(a3)).contains(new Status(v("v4"), false));
    assertThat(allAffected(EU, T, "v3", 5)).containsExactly(a2); // replay retries it
  }

  @Test
  @DisplayName("TG-11b FR-19: if markUnknown itself fails the EF is still in failed and never counted up to date")
  void failureNeverUpToDate() {
    chain();
    EfRef a1 = ef("a1", F);
    pend(EU, T, a1, "v1", "v3");
    reg.withdraw(T, v("v3"));
    BaselineLookup boom = (region, e, t) -> { throw new IllegalStateException("x"); };
    PendingUpdateMarker brokenMarker =
        new PendingUpdateMarker() {
          public com.caseware.fanout.port.MarkResult record(WorkKey k) { return marker.record(k); }
          public void markUnknown(WorkKey k, String why) { throw new IllegalStateException("store down"); }
          public com.caseware.fanout.port.MarkResult reevaluate(WorkKey k, EligibleResult r) { return marker.reevaluate(k, r); }
        };

    ReevaluationReport r = evaluator(brokenMarker, boom, flags, 2).onWithdrawn(EU, T, v("v3"));

    assertThat(r.failed()).hasSize(1);
    assertThat(r.upToDate()).isZero();
    assertThat(r.markedUnknown()).isZero();
    assertThat(allAffected(EU, T, "v3", 5)).containsExactly(a1);
  }

  /** Test double that fails on any call. It is wired into the baseline lookup and marker wrapper below. */
  private static final class FailingEfLoader {
    final AtomicInteger calls = new AtomicInteger();

    void load(EfRef ef) {
      calls.incrementAndGet();
      throw new AssertionError("EF loader must never be called (NFR-22)");
    }
  }

  @Test
  @DisplayName("TG-12 FR-13 NFR-22: zero loader calls, and no port exposes a load method")
  void noLoaderCalls() {
    FailingEfLoader loader = new FailingEfLoader();
    chain();
    EfRef a1 = ef("a1", F), a2 = ef("a2", F);
    pend(EU, T, a1, "v1", "v3");
    pend(EU, T, a2, "v1", "v3");
    reg.withdraw(T, v("v3"));

    // Every port the re-evaluator uses is wrapped so that any EF load attempt would be routed to the
    // failing loader; the re-evaluator has no such call, so the counter stays zero. The reflection
    // scan below is the structural guard.
    BaselineLookup guardedBaselines =
        (region, e, t) -> {
          Optional<VersionId> b = baselines.baselineOf(region, e, t);
          if (b.isEmpty()) {
            loader.load(e);
          }
          return b;
        };
    PendingUpdateMarker guardedMarker =
        new PendingUpdateMarker() {
          public com.caseware.fanout.port.MarkResult record(WorkKey k) { return marker.record(k); }
          public void markUnknown(WorkKey k, String why) {
            loader.load(ef(k.ef().value(), F)); // unknown must not need an EF load
            marker.markUnknown(k, why);
          }
          public com.caseware.fanout.port.MarkResult reevaluate(WorkKey k, EligibleResult r) { return marker.reevaluate(k, r); }
        };
    ReevaluationReport report = evaluator(guardedMarker, guardedBaselines, flags, 2).onWithdrawn(EU, T, v("v3"));

    assertThat(report.markedPending()).isEqualTo(2);
    assertThat(report.failed()).isEmpty();
    assertThat(loader.calls).hasValue(0);
    for (Class<?> c :
        List.of(
            WithdrawalReevaluator.class,
            PendingTargetIndex.class,
            BaselineLookup.class,
            PendingUpdateMarker.class,
            VersionGraph.class,
            VersionRegistry.class)) {
      for (Method m : c.getMethods()) {
        assertThat(m.getName().toLowerCase()).as(c.getSimpleName() + "." + m.getName()).doesNotContain("load");
      }
    }
  }

  private Map<com.caseware.fanout.domain.EfId, Status> finalState() {
    return Map.copyOf(marker.snapshot(T));
  }

  @Test
  @DisplayName("TG-16 NFR-27 NFR-32: withdrawal delivered 1, 2 and 5 times gives the single-delivery state")
  void repeatedWithdrawalSameState() {
    Set<Map<EfId, Status>> states = new HashSet<>();
    List<Set<EfRef>> indexes = new ArrayList<>();
    for (int times : new int[] {1, 2, 5}) {
      InMemoryVersionRegistry r = new InMemoryVersionRegistry();
      r.register(pub("v1", 1));
      r.register(pub("v2", 2, "v1"));
      r.register(pub("v3", 3, "v2"));
      r.register(pub("v4", 4, "v3"));
      InMemoryPendingTargetIndex idx = new InMemoryPendingTargetIndex();
      InMemoryPendingUpdateMarker m = new InMemoryPendingUpdateMarker(new GraphBackedVersionGraphReader(r));
      EfRef a1 = ef("a1", F), a2 = ef("a2", F);
      for (EfRef e : List.of(a1, a2)) {
        baselines.set(EU, e, T, v("v1"));
        m.record(new WorkKey(e.ef(), T, v("v3")));
        idx.record(EU, T, v("v3"), e);
      }
      WithdrawalReevaluator ev = new WithdrawalReevaluator(r, idx, baselines, m, flags, 1);
      r.withdraw(T, v("v3"));
      for (int i = 0; i < times; i++) {
        r.withdraw(T, v("v3")); // idempotent flag
        ev.onWithdrawn(EU, T, v("v3"));
      }
      states.add(Map.copyOf(m.snapshot(T)));
      indexes.add(new HashSet<>(idx.affectedBy(EU, T, v("v4"), Optional.empty(), 10).items()));
    }
    assertThat(states).hasSize(1);
    assertThat(states.iterator().next()).hasSize(2).containsValue(new Status(v("v4"), false));
    assertThat(indexes).allSatisfy(s -> assertThat(s).hasSize(2));
  }

  @Test
  @DisplayName("TG-16 NFR-27: repeat does no extra marker writes once converged")
  void repeatIsNoOp() {
    chain();
    EfRef a1 = ef("a1", F);
    pend(EU, T, a1, "v1", "v3");
    reg.withdraw(T, v("v3"));
    evaluator().onWithdrawn(EU, T, v("v3"));
    int calls = marker.totalRecordCalls();
    ReevaluationReport again = evaluator().onWithdrawn(EU, T, v("v3"));
    assertThat(again.evaluated()).isZero();
    assertThat(marker.totalRecordCalls()).isEqualTo(calls);
  }

  @Test
  @DisplayName("TG-16a NFR-32: withdraw v3 then v2 equals v2 then v3")
  void outOfOrderWithdrawals() {
    List<Map<EfId, Status>> results = new ArrayList<>();
    for (boolean v3First : new boolean[] {true, false}) {
      InMemoryVersionRegistry r = new InMemoryVersionRegistry();
      r.register(pub("v1", 1));
      r.register(pub("v2", 2, "v1"));
      r.register(pub("v3", 3, "v2"));
      r.register(pub("v4", 4, "v3"));
      InMemoryPendingTargetIndex idx = new InMemoryPendingTargetIndex();
      InMemoryPendingUpdateMarker m = new InMemoryPendingUpdateMarker(new GraphBackedVersionGraphReader(r));
      EfRef a = ef("a", F), b = ef("b", G);
      baselines.set(EU, a, T, v("v1"));
      baselines.set(EU, b, T, v("v1"));
      m.record(new WorkKey(a.ef(), T, v("v3")));
      idx.record(EU, T, v("v3"), a);
      m.record(new WorkKey(b.ef(), T, v("v2")));
      idx.record(EU, T, v("v2"), b);
      WithdrawalReevaluator ev = new WithdrawalReevaluator(r, idx, baselines, m, flags, 1);
      String[] order = v3First ? new String[] {"v3", "v2"} : new String[] {"v2", "v3"};
      for (String w : order) {
        r.withdraw(T, v(w));
        ev.onWithdrawn(EU, T, v(w));
      }
      results.add(Map.copyOf(m.snapshot(T)));
    }
    assertThat(results.get(0)).isEqualTo(results.get(1));
    assertThat(results.get(0).values()).containsOnly(new Status(v("v4"), false));
  }

  @Test
  @DisplayName("TG-16b NFR-32 FR-12: withdrawal of a version not in the registry is rejected, nothing written")
  void unknownVersionRejected() {
    chain();
    EfRef a1 = ef("a1", F);
    pend(EU, T, a1, "v1", "v3");
    Map<EfId, Status> before = finalState();

    assertThatThrownBy(() -> evaluator().onWithdrawn(EU, T, v("v9")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> evaluator().onWithdrawn(EU, T, v("v3"))) // not withdrawn yet
        .isInstanceOf(IllegalStateException.class);

    assertThat(finalState()).isEqualTo(before);
    assertThat(allAffected(EU, T, "v3", 5)).containsExactly(a1);
  }

  @Test
  @DisplayName("TG-17 NFR-41 ADR 0014: flag-off firm gets no write and is counted skipped; flag-on firm updated")
  void flagOffNoWrite() {
    chain();
    EfRef f1 = ef("f1", F), g1 = ef("g1", G);
    pend(EU, T, f1, "v1", "v3");
    pend(EU, T, g1, "v1", "v3");
    flags.setEnabled(F, false);
    int callsBefore = marker.totalReevaluateCalls();
    reg.withdraw(T, v("v3"));

    ReevaluationReport r = evaluator().onWithdrawn(EU, T, v("v3"));

    assertThat(r.skippedFlagOff()).isEqualTo(1);
    assertThat(r.markedPending()).isEqualTo(1);
    assertThat(status(f1)).contains(new Status(v("v3"), false));
    assertThat(status(g1)).contains(new Status(v("v4"), false));
    assertThat(marker.totalReevaluateCalls()).isEqualTo(callsBefore + 1);
    assertThat(allAffected(EU, T, "v3", 5)).containsExactly(f1);
  }

  @Test
  @DisplayName("TG-17a NFR-41: after the flag flips on, a replay produces the correct status")
  void flagFlipsOnReplay() {
    chain();
    EfRef f1 = ef("f1", F);
    pend(EU, T, f1, "v1", "v3");
    flags.setEnabled(F, false);
    reg.withdraw(T, v("v3"));
    evaluator().onWithdrawn(EU, T, v("v3"));

    flags.setEnabled(F, true);
    ReevaluationReport r = evaluator().onWithdrawn(EU, T, v("v3"));

    assertThat(r.markedPending()).isEqualTo(1);
    assertThat(status(f1)).contains(new Status(v("v4"), false));
  }
}
