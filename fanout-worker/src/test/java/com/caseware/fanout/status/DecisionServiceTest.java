package com.caseware.fanout.status;

import static com.caseware.fanout.graph.GraphFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.caseware.fanout.adapter.memory.InMemoryCaptureDeadLetterSink;
import com.caseware.fanout.adapter.memory.InMemoryCaptureOutbox;
import com.caseware.fanout.adapter.memory.InMemoryDecisionStore;
import com.caseware.fanout.adapter.memory.InMemoryEfFirmLookup;
import com.caseware.fanout.adapter.memory.InMemoryFirmFlags;
import com.caseware.fanout.adapter.memory.InMemoryStatusStore;
import com.caseware.fanout.adapter.memory.InMemoryTemplateVersionRecordStore;
import com.caseware.fanout.adapter.memory.InMemoryVersionRegistry;
import com.caseware.fanout.app.MutableClock;
import com.caseware.fanout.capture.CaptureHook;
import com.caseware.fanout.capture.CaptureHooks;
import com.caseware.fanout.capture.CaptureMetrics;
import com.caseware.fanout.capture.CaptureRelay;
import com.caseware.fanout.capture.CaptureSource;
import com.caseware.fanout.domain.EfId;
import com.caseware.fanout.domain.EfRef;
import com.caseware.fanout.domain.FirmId;
import com.caseware.fanout.domain.Region;
import com.caseware.fanout.domain.TemplateId;
import com.caseware.fanout.domain.VersionId;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DecisionServiceTest {

  private static final Region EU = Region.EU;
  private static final Instant T0 = Instant.parse("2026-10-30T10:00:00Z");
  private static final FirmId F = new FirmId("F");
  private static final EfRef EF1 = new EfRef(new EfId("ef1"), F);
  private static final EfRef FOREIGN = new EfRef(new EfId("ef9"), new FirmId("G"));
  private static final Caller ALICE = new Caller(F, "alice");

  private final InMemoryVersionRegistry reg = new InMemoryVersionRegistry();
  private final InMemoryTemplateVersionRecordStore records = new InMemoryTemplateVersionRecordStore();
  private final InMemoryDecisionStore decisions = new InMemoryDecisionStore();
  private final InMemoryStatusStore store = new InMemoryStatusStore();
  private final InMemoryFirmFlags flags = new InMemoryFirmFlags();
  private final InMemoryEfFirmLookup lookup = new InMemoryEfFirmLookup();
  private final MutableClock clock = new MutableClock(T0);
  private final CaptureMetrics metrics = new CaptureMetrics();
  private final InMemoryCaptureOutbox outbox =
      new InMemoryCaptureOutbox(clock, 5, 100, new InMemoryCaptureDeadLetterSink(clock), metrics);
  private final CaptureRelay relay =
      new CaptureRelay(records, outbox, clock, new SplittableRandom(3), metrics, CaptureRelay.Config.defaults());
  private final CaptureHooks realHook = new CaptureHooks(flags, outbox, metrics, 10);

  DecisionServiceTest() {
    reg.register(pub("v1", 1));
    reg.register(pub("v2", 2, "v1"));
    for (EfRef e : new EfRef[] {EF1, FOREIGN}) {
      lookup.add(e);
      records.upsertVersion(EU, e, T, v("v1"), T0, CaptureSource.CREATE);
    }
  }

  private StatusService status(StatusStore s) {
    return new StatusService(records, records, reg::snapshot, decisions, s, flags, lookup, clock);
  }

  private DecisionService svc(CaptureHook hook) {
    return new DecisionService(decisions, status(store), hook, flags, clock);
  }

  private UpdateStatus rowStatus() {
    return store.find(F, EF1.ef()).orElseThrow().status();
  }

  private static final class CountingHook implements CaptureHook {
    final AtomicInteger accepts = new AtomicInteger();
    final boolean fail;

    CountingHook(boolean fail) {
      this.fail = fail;
    }

    public void onCreate(Region r, EfRef e, TemplateId t, VersionId v, Instant at) {}

    public void onOpen(Region r, EfRef e, TemplateId t, VersionId v, Instant at) {}

    public void onSave(Region r, EfRef e, TemplateId t, VersionId v, Instant at) {}

    public void onAccept(Region r, EfRef e, TemplateId t, VersionId v, Instant at) {
      accepts.incrementAndGet();
      if (fail) {
        throw new IllegalStateException("hook down");
      }
    }
  }

  @Test
  @DisplayName("U-27 FR-27 FR-31: a decision records user, time, EF, version and type")
  void allFieldsRecorded() {
    DecisionService s = svc(new CountingHook(false));
    Instant at = T0.plusSeconds(42);
    assertThat(s.decline(EU, ALICE, EF1, T, v("v2"), at)).isEqualTo(DecisionResult.RECORDED);
    assertThat(decisions.history(EF1.ef(), T))
        .containsExactly(new Decision(EF1, T, v("v2"), DecisionType.DECLINE, "alice", at));
    s.accept(EU, new Caller(F, "bob"), EF1, T, v("v2"), at.plusSeconds(1));
    assertThat(decisions.history(EF1.ef(), T))
        .contains(new Decision(EF1, T, v("v2"), DecisionType.ACCEPT, "bob", at.plusSeconds(1)));
    assertThat(decisions.declined(EF1.ef(), T)).containsExactly(v("v2"));
    assertThat(decisions.accepted(EF1.ef(), T)).containsExactly(v("v2"));
  }

  @Test
  @DisplayName("U-29 FR-29 FR-30: decline leaves the baseline; a later version is pending from it")
  void declineKeepsBaseline() {
    status(store).refresh(EU, EF1);
    assertThat(rowStatus()).isEqualTo(UpdateStatus.UPDATE_PENDING);
    var before = records.find(EU, EF1).orElseThrow();
    svc(new CountingHook(false)).decline(EU, ALICE, EF1, T, v("v2"), T0);
    assertThat(records.find(EU, EF1).orElseThrow()).isEqualTo(before);
    assertThat(rowStatus()).isEqualTo(UpdateStatus.UP_TO_DATE);

    reg.register(pub("v3", 3, "v2"));
    status(store).refresh(EU, EF1);
    StatusRow row = store.find(F, EF1.ef()).orElseThrow();
    assertThat(row.status()).isEqualTo(UpdateStatus.UPDATE_PENDING);
    assertThat(row.baseline()).contains(v("v1"));
    assertThat(row.pendingTarget()).contains(v("v3"));
  }

  @Test
  @DisplayName("U-31 I-32 FR-31 FR-32 FR-4: accept shows up to date at once via the effective baseline, and after the relay")
  void acceptUsesEffectiveBaselineThenRelay() {
    status(store).refresh(EU, EF1);
    svc(realHook).accept(EU, ALICE, EF1, T, v("v2"), T0.plusSeconds(1));
    assertThat(records.find(EU, EF1).orElseThrow().baseline()).contains(v("v1")); // not yet applied
    StatusRow now = store.find(F, EF1.ef()).orElseThrow();
    assertThat(now.status()).isEqualTo(UpdateStatus.UP_TO_DATE);
    assertThat(now.baseline()).contains(v("v2"));

    assertThat(relay.drainOnce()).isEqualTo(1);
    assertThat(records.find(EU, EF1).orElseThrow().baseline()).contains(v("v2"));
    status(store).refresh(EU, EF1);
    assertThat(rowStatus()).isEqualTo(UpdateStatus.UP_TO_DATE);
    assertThat(store.find(F, EF1.ef()).orElseThrow().baseline()).contains(v("v2"));
  }

  @Test
  @DisplayName("I-32 FR-32: decline shows up to date")
  void declineRefreshes() {
    status(store).refresh(EU, EF1);
    svc(new CountingHook(false)).decline(EU, ALICE, EF1, T, v("v2"), T0);
    assertThat(rowStatus()).isEqualTo(UpdateStatus.UP_TO_DATE);
    assertThat(store.counts(F)).isEqualTo(new StatusCounts(1, 0, 0));
  }

  @Test
  @DisplayName("FR-27 FR-32: duplicate decline and duplicate accept are no-ops (no hook, no refresh)")
  void duplicates() {
    CountingHook hook = new CountingHook(false);
    DecisionService s = svc(hook);
    assertThat(s.decline(EU, ALICE, EF1, T, v("v2"), T0)).isEqualTo(DecisionResult.RECORDED);
    assertThat(s.decline(EU, new Caller(F, "bob"), EF1, T, v("v2"), T0.plusSeconds(9)))
        .isEqualTo(DecisionResult.DUPLICATE);
    assertThat(decisions.history(EF1.ef(), T)).hasSize(1);
    assertThat(decisions.history(EF1.ef(), T).get(0).user()).isEqualTo("alice");

    assertThat(s.accept(EU, ALICE, EF1, T, v("v2"), T0)).isEqualTo(DecisionResult.RECORDED);
    assertThat(s.accept(EU, ALICE, EF1, T, v("v2"), T0)).isEqualTo(DecisionResult.DUPLICATE);
    assertThat(hook.accepts).hasValue(1);

    // a duplicate does not refresh: overwrite the row and see it unchanged
    StatusRow marker =
        new StatusRow(F, EF1.ef(), java.util.Optional.of(T), UpdateStatus.UNKNOWN,
            java.util.Optional.empty(), java.util.Optional.empty(), T0, StatusSource.DERIVED);
    store.put(marker);
    s.decline(EU, ALICE, EF1, T, v("v2"), T0);
    assertThat(store.find(F, EF1.ef())).contains(marker);
  }

  @Test
  @DisplayName("FR-28 FR-31: decline then accept of the same version keeps both records, ends up to date")
  void declineThenAccept() {
    DecisionService s = svc(realHook);
    s.decline(EU, ALICE, EF1, T, v("v2"), T0);
    assertThat(s.accept(EU, ALICE, EF1, T, v("v2"), T0.plusSeconds(1))).isEqualTo(DecisionResult.RECORDED);
    assertThat(decisions.history(EF1.ef(), T)).extracting(Decision::type)
        .containsExactly(DecisionType.DECLINE, DecisionType.ACCEPT);
    assertThat(rowStatus()).isEqualTo(UpdateStatus.UP_TO_DATE);
    assertThat(store.find(F, EF1.ef()).orElseThrow().baseline()).contains(v("v2"));
  }

  @Test
  @DisplayName("NFR-12: concurrent identical decisions record exactly once and the row ends consistent")
  void concurrentDecisions() throws Exception {
    CountingHook hook = new CountingHook(false);
    DecisionService s = svc(hook);
    int n = 16;
    CountDownLatch go = new CountDownLatch(1);
    List<Future<DecisionResult>> fs = new ArrayList<>();
    try (ExecutorService ex = Executors.newFixedThreadPool(n)) {
      for (int i = 0; i < n; i++) {
        int k = i;
        fs.add(ex.submit(() -> {
          go.await();
          return k % 2 == 0
              ? s.accept(EU, ALICE, EF1, T, v("v2"), T0)
              : s.decline(EU, ALICE, EF1, T, v("v2"), T0);
        }));
      }
      go.countDown();
      int recorded = 0;
      for (Future<DecisionResult> f : fs) {
        if (f.get() == DecisionResult.RECORDED) {
          recorded++;
        }
      }
      assertThat(recorded).isEqualTo(2); // one accept, one decline
    }
    assertThat(hook.accepts).hasValue(1);
    assertThat(decisions.history(EF1.ef(), T)).hasSize(2);
    assertThat(rowStatus()).isEqualTo(UpdateStatus.UP_TO_DATE);
  }

  @Test
  @DisplayName("U-33 NFR-33: a decision on a foreign firm's EF is rejected and records nothing")
  void foreignFirm() {
    DecisionService s = svc(new CountingHook(false));
    assertThatThrownBy(() -> s.decline(EU, ALICE, FOREIGN, T, v("v2"), T0))
        .isInstanceOfSatisfying(DecisionRejectedException.class,
            e -> assertThat(e.reason()).isEqualTo(DecisionRejectedException.Reason.NOT_YOUR_FIRM));
    assertThatThrownBy(() -> s.accept(EU, ALICE, FOREIGN, T, v("v2"), T0))
        .isInstanceOf(DecisionRejectedException.class);
    assertThat(decisions.history(FOREIGN.ef(), T)).isEmpty();
    assertThat(store.find(FOREIGN.firm(), FOREIGN.ef())).isEmpty();
  }

  @Test
  @DisplayName("U-41 NFR-41: flag off rejects decisions, records nothing and writes no row")
  void flagOff() {
    flags.setEnabled(F, false);
    CountingHook hook = new CountingHook(false);
    DecisionService s = svc(hook);
    assertThatThrownBy(() -> s.decline(EU, ALICE, EF1, T, v("v2"), T0))
        .isInstanceOfSatisfying(DecisionRejectedException.class,
            e -> assertThat(e.reason()).isEqualTo(DecisionRejectedException.Reason.FEATURE_OFF));
    assertThatThrownBy(() -> s.accept(EU, ALICE, EF1, T, v("v2"), T0))
        .isInstanceOf(DecisionRejectedException.class);
    assertThat(decisions.history(EF1.ef(), T)).isEmpty();
    assertThat(hook.accepts).hasValue(0);
    assertThat(store.find(F, EF1.ef())).isEmpty();
  }

  @Test
  @DisplayName("NFR-30: a hook failure after the append does not undo the decision or propagate")
  void hookFailureDoesNotUndo() {
    status(store).refresh(EU, EF1);
    DecisionService s = svc(new CountingHook(true));
    assertThat(s.accept(EU, ALICE, EF1, T, v("v2"), T0)).isEqualTo(DecisionResult.RECORDED);
    assertThat(s.hookFailures()).isEqualTo(1);
    assertThat(decisions.accepted(EF1.ef(), T)).containsExactly(v("v2"));
    assertThat(rowStatus()).isEqualTo(UpdateStatus.UP_TO_DATE);
  }

  @Test
  @DisplayName("NFR-30: a refresh failure after the append does not undo the decision or propagate")
  void refreshFailureDoesNotUndo() {
    InMemoryStatusStore dead =
        new InMemoryStatusStore() {
          @Override
          public void put(StatusRow row) {
            throw new IllegalStateException("store down");
          }
        };
    DecisionService s = new DecisionService(decisions, status(dead), new CountingHook(false), flags, clock);
    assertThat(s.decline(EU, ALICE, EF1, T, v("v2"), T0)).isEqualTo(DecisionResult.RECORDED);
    assertThat(s.refreshFailures()).isEqualTo(1);
    assertThat(decisions.declined(EF1.ef(), T)).containsExactly(v("v2"));
    // reconciliation repairs the row
    assertThat(status(store).refreshFirm(EU, F)).isEqualTo(1);
    assertThat(rowStatus()).isEqualTo(UpdateStatus.UP_TO_DATE);
  }

  @Test
  @DisplayName("NFR-30 FR-25: a DecisionStore failure surfaces, leaves the row unchanged and calls no hook")
  void storeFailure() {
    status(store).refresh(EU, EF1);
    StatusRow before = store.find(F, EF1.ef()).orElseThrow();
    CountingHook hook = new CountingHook(false);
    DecisionStore broken =
        new DecisionStore() {
          public DecisionResult append(Decision d) {
            throw new IllegalStateException("decision store down");
          }

          public Set<VersionId> declined(EfId e, TemplateId t) {
            return Set.of();
          }

          public Set<VersionId> accepted(EfId e, TemplateId t) {
            return Set.of();
          }
        };
    DecisionService s = new DecisionService(broken, status(store), hook, flags, clock);
    assertThatThrownBy(() -> s.accept(EU, ALICE, EF1, T, v("v2"), T0))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> s.decline(EU, ALICE, EF1, T, v("v2"), T0))
        .isInstanceOf(IllegalStateException.class);
    assertThat(store.find(F, EF1.ef())).contains(before);
    assertThat(hook.accepts).hasValue(0);
  }

  @Test
  @DisplayName("U-34 NFR-34: the decision store has no mutation method beyond append")
  void appendOnly() {
    Set<String> allowed = Set.of("append", "declined", "accepted", "history");
    for (Class<?> c : new Class<?>[] {DecisionStore.class, InMemoryDecisionStore.class}) {
      Set<String> names = new TreeSet<>();
      for (Method m : c.getMethods()) {
        if (m.getDeclaringClass() != Object.class) {
          names.add(m.getName());
        }
      }
      assertThat(allowed).containsAll(names);
      assertThat(names).contains("append");
      assertThat(names).noneMatch(n -> n.matches("(?i).*(update|delete|remove|put|clear|set|replace|merge).*"));
    }
  }
}
