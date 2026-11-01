package com.caseware.fanout.status;

import static com.caseware.fanout.graph.GraphFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.caseware.fanout.adapter.memory.InMemoryVersionRegistry;
import com.caseware.fanout.capture.CaptureSource;
import com.caseware.fanout.capture.TemplateVersionRecord;
import com.caseware.fanout.domain.EfId;
import com.caseware.fanout.domain.EfRef;
import com.caseware.fanout.domain.FirmId;
import com.caseware.fanout.domain.Region;
import com.caseware.fanout.domain.VersionId;
import com.caseware.fanout.graph.TemplateVersion;
import com.caseware.fanout.graph.VersionGraph;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StatusDeriverTest {

  private final InMemoryVersionRegistry reg = new InMemoryVersionRegistry();

  private void register(TemplateVersion... vs) {
    for (TemplateVersion v : vs) {
      reg.register(v);
    }
  }

  private static Optional<TemplateVersionRecord> rec(String baseline) {
    Instant t = Instant.parse("2026-10-30T10:00:00Z");
    return Optional.of(
        new TemplateVersionRecord(
            Region.EU,
            new EfRef(new EfId("ef"), new FirmId("f")),
            T,
            v("v1"),
            baseline == null ? Optional.empty() : Optional.of(v(baseline)),
            t,
            baseline == null ? Optional.empty() : Optional.of(t),
            CaptureSource.OPEN));
  }

  private Derived derive(Optional<TemplateVersionRecord> r, Set<VersionId> declined, Set<VersionId> accepted) {
    return StatusDeriver.derive(r, reg.snapshot(), declined, accepted);
  }

  private static Derived pending(String base, String target) {
    return new Derived(UpdateStatus.UPDATE_PENDING, Optional.of(v(base)), Optional.of(v(target)));
  }

  @Test
  @DisplayName("U-24 FR-24: no template-version record is UNKNOWN")
  void noRecord() {
    register(pub("v1", 1));
    assertThat(derive(Optional.empty(), Set.of(), Set.of()).status()).isEqualTo(UpdateStatus.UNKNOWN);
  }

  @Test
  @DisplayName("U-25 FR-25: record without baseline is UNKNOWN")
  void noBaseline() {
    register(pub("v1", 1), pub("v2", 2, "v1"));
    assertThat(derive(rec(null), Set.of(), Set.of()).status()).isEqualTo(UpdateStatus.UNKNOWN);
  }

  @Test
  @DisplayName("U-23 FR-23: nothing newer is UP_TO_DATE")
  void nothingNewer() {
    register(pub("v1", 1));
    Derived d = derive(rec("v1"), Set.of(), Set.of());
    assertThat(d).isEqualTo(new Derived(UpdateStatus.UP_TO_DATE, Optional.of(v("v1")), Optional.empty()));
  }

  @Test
  @DisplayName("U-23 FR-23: newer eligible version is UPDATE_PENDING from the baseline")
  void newerEligible() {
    register(pub("v1", 1), pub("v2", 2, "v1"));
    assertThat(derive(rec("v1"), Set.of(), Set.of())).isEqualTo(pending("v1", "v2"));
  }

  @Test
  @DisplayName("U-28 FR-28 Q8: declined latest eligible is UP_TO_DATE and the baseline is unchanged (FR-29)")
  void declinedLatest() {
    register(pub("v1", 1), pub("v5", 5, "v1"));
    Derived d = derive(rec("v1"), Set.of(v("v5")), Set.of());
    assertThat(d.status()).isEqualTo(UpdateStatus.UP_TO_DATE);
    assertThat(d.baseline()).contains(v("v1"));
  }

  @Test
  @DisplayName("U-30 FR-30: v5 declined, v7 later published: pending v7 from the baseline")
  void declinedThenLater() {
    register(pub("v1", 1), pub("v5", 5, "v1"), pub("v7", 7, "v5"));
    assertThat(derive(rec("v1"), Set.of(v("v5")), Set.of())).isEqualTo(pending("v1", "v7"));
  }

  @Test
  @DisplayName("U-23 FR-12: unreachable later version is UNKNOWN, not UP_TO_DATE")
  void unreachable() {
    register(pub("v0", 0), pub("v1", 1), pub("v2", 2, "v1"), pub("v3", 3, "v0"));
    Derived d = StatusDeriver.derive(rec("v2"), reg.snapshot(), Set.of(), Set.of());
    assertThat(d.status()).isEqualTo(UpdateStatus.UNKNOWN);
  }

  @Test
  @DisplayName("U-23 FR-13: withdrawn latest is not the pending target")
  void withdrawnTarget() {
    register(pub("v1", 1), pub("v2", 2, "v1"), pub("v3", 3, "v2"));
    reg.withdraw(T, v("v3"));
    assertThat(derive(rec("v1"), Set.of(), Set.of())).isEqualTo(pending("v1", "v2"));
  }

  @Test
  @DisplayName("U-26 FR-26: three accumulated versions are one pending update to the latest")
  void accumulated() {
    register(pub("v1", 1), pub("v2", 2, "v1"), pub("v3", 3, "v2"), pub("v4", 4, "v3"));
    Derived d = derive(rec("v1"), Set.of(), Set.of());
    assertThat(d).isEqualTo(pending("v1", "v4"));
    assertThat(Derived.class.getRecordComponents()).hasSize(3); // no version count
  }

  @Test
  @DisplayName("U-25 FR-25: a thrown exception is UNKNOWN, never UP_TO_DATE")
  void thrown() {
    register(pub("v1", 1));
    assertThat(StatusDeriver.derive(rec("v1"), null, Set.of(), Set.of()).status())
        .isEqualTo(UpdateStatus.UNKNOWN);
    // baseline version the graph does not know throws IAE inside the graph
    assertThat(derive(rec("v9"), Set.of(), Set.of()).status()).isEqualTo(UpdateStatus.UNKNOWN);
    VersionGraph boom =
        new VersionGraph() {
          public Optional<TemplateVersion> find(com.caseware.fanout.domain.TemplateId t, VersionId v) {
            throw new IllegalStateException("x");
          }

          public java.util.Collection<TemplateVersion> versions(com.caseware.fanout.domain.TemplateId t) {
            throw new IllegalStateException("x");
          }

          public com.caseware.fanout.graph.EligibleResult eligibleVersion(
              com.caseware.fanout.domain.TemplateId t, VersionId b) {
            throw new IllegalStateException("x");
          }

          public com.caseware.fanout.graph.EligibleResult eligibleVersion(
              com.caseware.fanout.domain.TemplateId t, VersionId b, com.caseware.fanout.graph.MarketBranch m) {
            throw new IllegalStateException("x");
          }

          public boolean isNewer(com.caseware.fanout.domain.TemplateId t, VersionId c, VersionId cur) {
            throw new IllegalStateException("x");
          }
        };
    assertThat(StatusDeriver.derive(rec("v1"), boom, Set.of(), Set.of()).status())
        .isEqualTo(UpdateStatus.UNKNOWN);
  }

  @Test
  @DisplayName("U-31 FR-23: accepted version newer than the record baseline is the effective baseline")
  void acceptedNewer() {
    register(pub("v1", 1), pub("v2", 2, "v1"), pub("v3", 3, "v2"), pub("v4", 4, "v3"));
    assertThat(derive(rec("v1"), Set.of(), Set.of(v("v3")))).isEqualTo(pending("v3", "v4"));
    assertThat(derive(rec("v1"), Set.of(), Set.of(v("v4"))).status()).isEqualTo(UpdateStatus.UP_TO_DATE);
    // an accepted version older than the baseline never moves it back
    assertThat(derive(rec("v3"), Set.of(), Set.of(v("v1")))).isEqualTo(pending("v3", "v4"));
  }

  @Test
  @DisplayName("U-22 FR-22: the status enum has exactly three values")
  void enumExhaustive() {
    assertThat(UpdateStatus.values())
        .containsExactly(UpdateStatus.UP_TO_DATE, UpdateStatus.UPDATE_PENDING, UpdateStatus.UNKNOWN);
    for (UpdateStatus s : UpdateStatus.values()) {
      String label =
          switch (s) {
            case UP_TO_DATE -> "up to date";
            case UPDATE_PENDING -> "update pending";
            case UNKNOWN -> "unknown";
          };
      assertThat(label).isNotBlank();
    }
  }

  @Test
  @DisplayName("FR-25 defensive: an accepted version unknown to the graph is skipped, not UNKNOWN")
  void unknownAcceptedIsSkipped() {
    register(pub("v1", 1), pub("v2", 2, "v1"));
    assertThat(derive(rec("v1"), Set.of(), Set.of(v("v99")))).isEqualTo(pending("v1", "v2"));
    assertThat(derive(rec("v1"), Set.of(), Set.of(v("v99"), v("v2")))
            .status()).isEqualTo(UpdateStatus.UP_TO_DATE);
  }

  @Test
  @DisplayName("FR-25 defensive: a declined version unknown to the graph is skipped")
  void unknownDeclinedIsSkipped() {
    register(pub("v1", 1), pub("v2", 2, "v1"));
    assertThat(derive(rec("v1"), Set.of(v("v99")), Set.of())).isEqualTo(pending("v1", "v2"));
  }

  @Test
  @DisplayName("FR-25: a graph failure of another cause (unknown baseline) is still UNKNOWN")
  void unknownBaselineStillUnknown() {
    register(pub("v1", 1));
    assertThat(derive(rec("v77"), Set.of(), Set.of(v("v1"))).status()).isEqualTo(UpdateStatus.UNKNOWN);
  }

  @Test
  @DisplayName("U-23 row invariant: pendingTarget is present iff UPDATE_PENDING")
  void rowInvariant() {
    Instant t = Instant.EPOCH;
    FirmId f = new FirmId("f");
    EfId e = new EfId("e");
    assertThatThrownBy(
            () ->
                new StatusRow(f, e, Optional.empty(), UpdateStatus.UNKNOWN, Optional.empty(),
                    Optional.of(v("v2")), t, StatusSource.DERIVED))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new StatusRow(f, e, Optional.empty(), UpdateStatus.UPDATE_PENDING, Optional.empty(),
                    Optional.empty(), t, StatusSource.DERIVED))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
