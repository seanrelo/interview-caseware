package com.caseware.fanout.adapter.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.fanout.domain.EfId;
import com.caseware.fanout.domain.TemplateId;
import com.caseware.fanout.domain.VersionId;
import com.caseware.fanout.domain.WorkKey;
import com.caseware.fanout.graph.EligibleResult;
import com.caseware.fanout.port.MarkResult;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InMemoryPendingUpdateMarkerTest {

  private static final Instant T0 = Instant.parse("2026-10-30T10:00:00Z");
  private static final TemplateId TPL = new TemplateId("t");
  private static final EfId EF = new EfId("ef");
  private final InMemoryVersionGraphReader graph = new InMemoryVersionGraphReader();
  private final InMemoryPendingUpdateMarker marker = new InMemoryPendingUpdateMarker(graph);

  private WorkKey k(String v) {
    return new WorkKey(EF, TPL, new VersionId(v));
  }

  private InMemoryPendingUpdateMarker.Status st(String v, boolean unknown) {
    return new InMemoryPendingUpdateMarker.Status(new VersionId(v), unknown);
  }

  InMemoryPendingUpdateMarkerTest() {
    graph.addVersion(TPL, new VersionId("v5"), T0, true);
    graph.addVersion(TPL, new VersionId("v7"), T0.plusSeconds(10), true);
  }

  @Test
  @DisplayName("NFR-12: record is monotonic: older and equal targets are UNCHANGED")
  void monotonicRecord() {
    assertThat(marker.record(k("v5"))).isEqualTo(MarkResult.APPLIED);
    assertThat(marker.record(k("v7"))).isEqualTo(MarkResult.APPLIED);
    assertThat(marker.record(k("v5"))).isEqualTo(MarkResult.UNCHANGED);
    assertThat(marker.record(k("v7"))).isEqualTo(MarkResult.UNCHANGED);
    assertThat(marker.status(EF, TPL)).contains(st("v7", false));
  }

  @Test
  @DisplayName("ADR 0006 c2: markUnknown never overwrites a newer pending target")
  void unknownIsMonotonic() {
    marker.record(k("v7"));
    marker.markUnknown(k("v5"), "dlq");
    assertThat(marker.status(EF, TPL)).contains(st("v7", false));

    marker.markUnknown(k("v7"), "dlq");
    assertThat(marker.status(EF, TPL)).contains(st("v7", true));
  }

  @Test
  @DisplayName("FR-19: unknown is cleared by a successful record of the same or newer target")
  void unknownCleared() {
    marker.markUnknown(k("v5"), "dlq");
    assertThat(marker.status(EF, TPL)).contains(st("v5", true));
    assertThat(marker.record(k("v5"))).isEqualTo(MarkResult.APPLIED);
    assertThat(marker.status(EF, TPL)).contains(st("v5", false));
  }

  private static EligibleResult elig(String v) {
    return new EligibleResult.Eligible(new VersionId(v));
  }

  @Test
  @DisplayName("ADR 0006: reevaluate applies only when stored target is exactly the withdrawn one")
  void reevaluateIsCasOnWithdrawnTarget() {
    marker.record(k("v7"));
    assertThat(marker.reevaluate(k("v5"), elig("v5"))).isEqualTo(MarkResult.UNCHANGED);
    assertThat(marker.reevaluate(k("v5"), new EligibleResult.UpToDate())).isEqualTo(MarkResult.UNCHANGED);
    assertThat(marker.reevaluate(k("v5"), new EligibleResult.Unknown("x"))).isEqualTo(MarkResult.UNCHANGED);
    assertThat(marker.status(EF, TPL)).contains(st("v7", false));
    EfId other = new EfId("none");
    assertThat(marker.reevaluate(new WorkKey(other, TPL, new VersionId("v7")), elig("v5")))
        .isEqualTo(MarkResult.UNCHANGED);
    assertThat(marker.status(other, TPL)).isEmpty();
  }

  @Test
  @DisplayName("ADR 0006: older Eligible is allowed through reevaluate only, and replay is idempotent")
  void olderEligibleOnlyThroughReevaluate() {
    marker.record(k("v7"));
    assertThat(marker.record(k("v5"))).isEqualTo(MarkResult.UNCHANGED);
    assertThat(marker.reevaluate(k("v7"), elig("v5"))).isEqualTo(MarkResult.APPLIED);
    assertThat(marker.status(EF, TPL)).contains(st("v5", false));
    assertThat(marker.reevaluate(k("v7"), elig("v5"))).isEqualTo(MarkResult.UNCHANGED);
    assertThat(marker.status(EF, TPL)).contains(st("v5", false));
    // A newer pending written meanwhile is never overwritten by a stale replay.
    marker.record(k("v7"));
    assertThat(marker.reevaluate(k("v7"), elig("v5"))).isEqualTo(MarkResult.APPLIED);
    marker.record(k("v7"));
    marker.markUnknown(k("v5"), "x");
    assertThat(marker.status(EF, TPL)).contains(st("v7", false));
  }

  @Test
  @DisplayName("ADR 0006: UpToDate clears pending; stale record of the same target is not re-applied; newer works")
  void upToDateClears() {
    graph.addVersion(TPL, new VersionId("v9"), T0.plusSeconds(20), true);
    marker.record(k("v7"));
    assertThat(marker.reevaluate(k("v7"), new EligibleResult.UpToDate())).isEqualTo(MarkResult.APPLIED);
    assertThat(marker.status(EF, TPL))
        .contains(new InMemoryPendingUpdateMarker.Status(new VersionId("v7"), false, true));
    assertThat(marker.reevaluate(k("v7"), new EligibleResult.UpToDate())).isEqualTo(MarkResult.UNCHANGED);
    assertThat(marker.record(k("v7"))).isEqualTo(MarkResult.UNCHANGED);
    assertThat(marker.record(k("v5"))).isEqualTo(MarkResult.UNCHANGED);
    assertThat(marker.record(k("v9"))).isEqualTo(MarkResult.APPLIED);
    assertThat(marker.status(EF, TPL)).contains(st("v9", false));
  }

  @Test
  @DisplayName("ADR 0006: Unknown result sets unknown on the withdrawn target, idempotently")
  void reevaluateUnknown() {
    marker.record(k("v7"));
    assertThat(marker.reevaluate(k("v7"), new EligibleResult.Unknown("r"))).isEqualTo(MarkResult.APPLIED);
    assertThat(marker.status(EF, TPL)).contains(st("v7", true));
    assertThat(marker.reevaluate(k("v7"), new EligibleResult.Unknown("r"))).isEqualTo(MarkResult.UNCHANGED);
    // Unknown on the withdrawn target can still be resolved by a later reevaluate.
    assertThat(marker.reevaluate(k("v7"), elig("v5"))).isEqualTo(MarkResult.APPLIED);
    assertThat(marker.status(EF, TPL)).contains(st("v5", false));
  }
}
