package com.caseware.fanout.adapter.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.caseware.fanout.domain.TemplateId;
import com.caseware.fanout.domain.VersionId;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InMemoryVersionGraphReaderTest {

  private static final Instant T0 = Instant.parse("2026-10-30T10:00:00Z");
  private static final TemplateId TPL = new TemplateId("t");
  private final InMemoryVersionGraphReader graph = new InMemoryVersionGraphReader();

  private static VersionId v(String s) {
    return new VersionId(s);
  }

  @Test
  @DisplayName("FR-20 support: latestEligible is empty for unknown templates")
  void unknownTemplate() {
    assertThat(graph.latestEligible(TPL)).isEmpty();
  }

  @Test
  @DisplayName("FR-20 support: latest eligible is by publish time, not by id")
  void latestByPublishTime() {
    graph.addVersion(TPL, v("v9"), T0, true);
    graph.addVersion(TPL, v("v10"), T0.plusSeconds(1), true);
    graph.addVersion(TPL, v("v2"), T0.minusSeconds(5), true);

    assertThat(graph.latestEligible(TPL)).contains(v("v10"));
  }

  @Test
  @DisplayName("Q3: equal publish time tie-breaks by version id")
  void tieBreak() {
    graph.addVersion(TPL, v("a"), T0, true);
    graph.addVersion(TPL, v("b"), T0, true);

    assertThat(graph.latestEligible(TPL)).contains(v("b"));
    assertThat(graph.isNewer(TPL, v("b"), v("a"))).isTrue();
    assertThat(graph.isNewer(TPL, v("a"), v("b"))).isFalse();
  }

  @Test
  @DisplayName("FR-9: withdrawn and ineligible versions are never latest")
  void withdrawn() {
    graph.addVersion(TPL, v("v5"), T0, true);
    graph.addVersion(TPL, v("v7"), T0.plusSeconds(10), true);
    graph.addVersion(TPL, v("v8"), T0.plusSeconds(20), false);
    assertThat(graph.latestEligible(TPL)).contains(v("v7"));

    graph.withdraw(TPL, v("v7"));
    assertThat(graph.latestEligible(TPL)).contains(v("v5"));
    graph.withdraw(TPL, v("v5"));
    assertThat(graph.latestEligible(TPL)).isEmpty();
    graph.withdraw(new TemplateId("none"), v("x")); // no-op
  }

  @Test
  @DisplayName("isNewer is irreflexive and still works for withdrawn versions")
  void isNewerBasics() {
    graph.addVersion(TPL, v("v5"), T0, true);
    graph.addVersion(TPL, v("v7"), T0.plusSeconds(10), true);
    graph.withdraw(TPL, v("v7"));

    assertThat(graph.isNewer(TPL, v("v7"), v("v5"))).isTrue();
    assertThat(graph.isNewer(TPL, v("v5"), v("v5"))).isFalse();
  }

  @Test
  @DisplayName("isNewer rejects unknown versions instead of guessing")
  void unknownVersionThrows() {
    graph.addVersion(TPL, v("v5"), T0, true);
    assertThatThrownBy(() -> graph.isNewer(TPL, v("v5"), v("zz")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("FR-20: isEligible is false for a withdrawn version and throws for an unknown one")
  void eligibility() {
    TemplateId t = new TemplateId("t");
    graph.addVersion(t, new VersionId("v1"), Instant.parse("2026-01-01T00:00:00Z"), true);
    assertThat(graph.isEligible(t, new VersionId("v1"))).isTrue();
    graph.withdraw(t, new VersionId("v1"));
    assertThat(graph.isEligible(t, new VersionId("v1"))).isFalse();
    assertThatThrownBy(() -> graph.isEligible(t, new VersionId("nope")))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
