package com.caseware.fanout.status;

import static com.caseware.fanout.graph.GraphFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.caseware.fanout.adapter.memory.InMemoryDecisionStore;
import com.caseware.fanout.adapter.memory.InMemoryEfFirmLookup;
import com.caseware.fanout.adapter.memory.InMemoryFirmFlags;
import com.caseware.fanout.adapter.memory.InMemoryStatusStore;
import com.caseware.fanout.adapter.memory.InMemoryTemplateVersionRecordStore;
import com.caseware.fanout.adapter.memory.InMemoryVersionRegistry;
import com.caseware.fanout.app.MutableClock;
import com.caseware.fanout.capture.CaptureHook;
import com.caseware.fanout.capture.CaptureSource;
import com.caseware.fanout.domain.EfId;
import com.caseware.fanout.domain.EfRef;
import com.caseware.fanout.domain.FirmId;
import com.caseware.fanout.domain.Region;
import com.caseware.fanout.domain.TemplateId;
import com.caseware.fanout.domain.VersionId;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Regression tests for defects Q1 (NFR-33) and Q2 (FR-25, FR-23) found by QA in spec 04 review. */
class QaProbeDecisionIntegrityTest {

  private static final Instant T0 = Instant.parse("2026-10-30T10:00:00Z");
  private static final FirmId F = new FirmId("F");
  private static final FirmId G = new FirmId("G");
  private static final EfRef MINE = new EfRef(new EfId("ef1"), F);
  private static final EfRef THEIRS = new EfRef(new EfId("ef9"), G);

  private final InMemoryVersionRegistry reg = new InMemoryVersionRegistry();
  private final InMemoryTemplateVersionRecordStore records = new InMemoryTemplateVersionRecordStore();
  private final InMemoryDecisionStore decisions = new InMemoryDecisionStore();
  private final InMemoryStatusStore store = new InMemoryStatusStore();
  private final InMemoryFirmFlags flags = new InMemoryFirmFlags();
  private final InMemoryEfFirmLookup lookup = new InMemoryEfFirmLookup();
  private final MutableClock clock = new MutableClock(T0);
  private final CaptureHook noopHook =
      new CaptureHook() {
        public void onCreate(Region r, EfRef e, TemplateId t, VersionId v, Instant at) {}

        public void onOpen(Region r, EfRef e, TemplateId t, VersionId v, Instant at) {}

        public void onSave(Region r, EfRef e, TemplateId t, VersionId v, Instant at) {}

        public void onAccept(Region r, EfRef e, TemplateId t, VersionId v, Instant at) {}
      };

  QaProbeDecisionIntegrityTest() {
    reg.register(pub("v1", 1));
    reg.register(pub("v2", 2, "v1"));
    for (EfRef e : new EfRef[] {MINE, THEIRS}) {
      lookup.add(e);
      records.upsertVersion(Region.EU, e, T, v("v1"), T0, CaptureSource.CREATE);
    }
  }

  private DecisionService svc() {
    StatusService s = new StatusService(records, records, reg::snapshot, decisions, store, flags, lookup, clock);
    return new DecisionService(decisions, s, noopHook, flags, clock);
  }

  @Test
  @DisplayName("NFR-33 regression: firm F cannot decide for an EF id owned by firm G with its own firm id")
  void craftedEfRefCannotDecideForForeignEf() {
    EfRef crafted = new EfRef(THEIRS.ef(), F); // client supplies the EF id, caller supplies own firm
    assertThatThrownBy(() -> svc().decline(Region.EU, new Caller(F, "mallory"), crafted, T, v("v2"), T0))
        .isInstanceOfSatisfying(
            DecisionRejectedException.class,
            e -> assertThat(e.reason()).isEqualTo(DecisionRejectedException.Reason.NOT_YOUR_FIRM));
    assertThat(decisions.declined(THEIRS.ef(), T)).isEmpty();
    assertThat(decisions.declined(crafted.ef(), T)).isEmpty();
    assertThat(store.find(F, crafted.ef())).isEmpty();
    assertThat(store.find(G, crafted.ef())).isEmpty();
  }

  @Test
  @DisplayName("NFR-33: an EF unknown to the lookup is never accepted as the caller's; no hook, nothing stored")
  void unknownEfIsRejected() {
    int[] hookCalls = {0};
    CaptureHook counting =
        new CaptureHook() {
          public void onCreate(Region r, EfRef e, TemplateId t, VersionId v, Instant at) {}

          public void onOpen(Region r, EfRef e, TemplateId t, VersionId v, Instant at) {}

          public void onSave(Region r, EfRef e, TemplateId t, VersionId v, Instant at) {}

          public void onAccept(Region r, EfRef e, TemplateId t, VersionId v, Instant at) {
            hookCalls[0]++;
          }
        };
    StatusService s = new StatusService(records, records, reg::snapshot, decisions, store, flags, lookup, clock);
    DecisionService d = new DecisionService(decisions, s, counting, flags, clock);
    EfRef ghost = new EfRef(new EfId("ghost"), F);
    assertThatThrownBy(() -> d.accept(Region.EU, new Caller(F, "alice"), ghost, T, v("v2"), T0))
        .isInstanceOfSatisfying(
            DecisionRejectedException.class,
            e -> assertThat(e.reason()).isEqualTo(DecisionRejectedException.Reason.NOT_YOUR_FIRM));
    assertThat(decisions.accepted(ghost.ef(), T)).isEmpty();
    assertThat(hookCalls[0]).isZero();
    assertThat(store.find(F, ghost.ef())).isEmpty();
  }

  @Test
  @DisplayName("FR-25/FR-23 regression: an accept of a version unknown to the graph is rejected and the EF stays pending")
  void bogusAcceptDoesNotPoisonStatus() {
    DecisionService d = svc();
    assertThatThrownBy(() -> d.accept(Region.EU, new Caller(F, "alice"), MINE, T, v("v99"), T0))
        .isInstanceOfSatisfying(
            DecisionRejectedException.class,
            e -> assertThat(e.reason()).isEqualTo(DecisionRejectedException.Reason.UNKNOWN_VERSION));
    assertThat(decisions.accepted(MINE.ef(), T)).isEmpty();
    assertThatThrownBy(() -> d.decline(Region.EU, new Caller(F, "alice"), MINE, T, v("v99"), T0))
        .isInstanceOf(DecisionRejectedException.class);
    assertThat(decisions.declined(MINE.ef(), T)).isEmpty();
    // A later refresh still derives the real status.
    StatusService s = new StatusService(records, records, reg::snapshot, decisions, store, flags, lookup, clock);
    s.refresh(Region.EU, MINE);
    StatusRow row = store.find(F, MINE.ef()).orElseThrow();
    assertThat(row.status()).isEqualTo(UpdateStatus.UPDATE_PENDING);
  }
}
