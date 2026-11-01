package com.caseware.fanout.graph;

import static com.caseware.fanout.graph.GraphFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.caseware.fanout.adapter.memory.InMemoryVersionRegistry;
import com.caseware.fanout.graph.EligibleResult.Eligible;
import com.caseware.fanout.graph.EligibleResult.Unknown;
import com.caseware.fanout.graph.EligibleResult.UpToDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VersionGraphTest {

  private final InMemoryVersionRegistry reg = new InMemoryVersionRegistry();

  private void register(TemplateVersion... vs) {
    for (TemplateVersion v : vs) {
      reg.register(v);
    }
  }

  @Test
  @DisplayName("TG-1 FR-8: register and read back round trip")
  void roundTrip() {
    TemplateVersion v1 = pub("v1", 1);
    TemplateVersion v2 = pub("v2", 2, "v1");
    register(v1, v2);
    assertThat(reg.snapshot().find(T, v("v2"))).contains(v2);
    assertThat(reg.snapshot().find(T, v("v9"))).isEmpty();
    assertThat(reg.snapshot().versions(T)).containsExactly(v1, v2);
    assertThat(reg.publish(T, v("v2"))).isEqualTo(new GraphPublishEvent(T, v("v2")));
  }

  @Test
  @DisplayName("TG-2 NFR-6: publish event has exactly two record components")
  void eventShape() {
    assertThat(GraphPublishEvent.class.getRecordComponents())
        .extracting(c -> c.getName())
        .containsExactly("templateId", "version");
  }

  @Test
  @DisplayName("TG-3 NFR-7: withdraw keeps node and edges")
  void withdrawKeepsNode() {
    register(pub("v1", 1), pub("v2", 2, "v1"), pub("v3", 3, "v2"));
    reg.withdraw(T, v("v2"));
    TemplateVersion w = reg.snapshot().find(T, v("v2")).orElseThrow();
    assertThat(w.status()).isEqualTo(VersionStatus.WITHDRAWN);
    assertThat(w.parents()).containsExactly(v("v1"));
    assertThat(reg.snapshot().find(T, v("v3")).orElseThrow().parents()).containsExactly(v("v2"));
  }

  @Test
  @DisplayName("TG-4 FR-9: market branches are selected independently")
  void branches() {
    register(
        pub("v1", 1),
        pub("v2", 2, "v1"),
        node("ca2", 3, CA, VersionStatus.PUBLISHED, "v1"),
        pub("v4", 4, "v2"));
    VersionGraph g = reg.snapshot();
    assertThat(g.eligibleVersion(T, v("v1"))).isEqualTo(new Eligible(v("v4")));
    assertThat(g.eligibleVersion(T, v("v1"), MAIN)).isEqualTo(new Eligible(v("v4")));
    assertThat(g.eligibleVersion(T, v("v1"), CA)).isEqualTo(new Eligible(v("ca2")));
  }

  @Test
  @DisplayName("TG-5 FR-10: withdrawn leaf is skipped")
  void withdrawnLeafSkipped() {
    register(pub("v1", 1), pub("v2", 2, "v1"), pub("v3", 3, "v2"));
    reg.withdraw(T, v("v3"));
    assertThat(reg.snapshot().eligibleVersion(T, v("v1"))).isEqualTo(new Eligible(v("v2")));
  }

  @Test
  @DisplayName("TG-6 FR-10: path through withdrawn v6 reaches v7")
  void pathViaWithdrawn() {
    register(pub("v5", 5), pub("v6", 6, "v5"), pub("v7", 7, "v6"));
    reg.withdraw(T, v("v6"));
    assertThat(reg.snapshot().eligibleVersion(T, v("v5"))).isEqualTo(new Eligible(v("v7")));
  }

  @Test
  @DisplayName("TG-7 FR-11: v7 containing declined v5 is reachable from both lineages")
  void v7ContainsV5() {
    register(pub("v1", 1), pub("v5", 5, "v1"), pub("v6", 6, "v1"), pub("v7", 7, "v5", "v6"));
    VersionGraph g = reg.snapshot();
    assertThat(g.eligibleVersion(T, v("v5"))).isEqualTo(new Eligible(v("v7")));
    assertThat(g.eligibleVersion(T, v("v6"))).isEqualTo(new Eligible(v("v7")));
    assertThat(g.eligibleVersion(T, v("v1"))).isEqualTo(new Eligible(v("v7")));
  }

  @Test
  @DisplayName("TG-8 FR-12: newer version not reachable gives Unknown, never UpToDate")
  void unreachableIsUnknown() {
    register(pub("v0", 0), pub("v1", 1), pub("v2", 2, "v1"), pub("v3", 3, "v0"));
    assertThat(reg.snapshot().eligibleVersion(T, v("v2"))).isInstanceOf(Unknown.class);
  }

  @Test
  @DisplayName("TG-9 FR-12: nothing newer gives UpToDate")
  void upToDate() {
    register(pub("v1", 1), pub("v2", 2, "v1"));
    assertThat(reg.snapshot().eligibleVersion(T, v("v2"))).isEqualTo(new UpToDate());
    assertThat(reg.snapshot().eligibleVersion(T, v("v2"), CA)).isEqualTo(new UpToDate());
  }

  @Test
  @DisplayName("FR-12: unknown baseline throws, never UpToDate")
  void unknownBaseline() {
    register(pub("v1", 1));
    assertThatThrownBy(() -> reg.snapshot().eligibleVersion(T, v("zz")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> reg.snapshot().isNewer(T, v("v1"), v("zz")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("TG-14 NFR-7: withdrawn content and diff stay readable")
  void contentSurvivesWithdrawal() {
    register(pub("v1", 1));
    VersionContent c = new VersionContent(T, v("v1"), "s3://c", "s3://d");
    reg.registerContent(c);
    reg.withdraw(T, v("v1"));
    assertThat(reg.content(T, v("v1"))).contains(c);
    assertThat(reg.content(T, v("v2"))).isEmpty();
  }

  @Test
  @DisplayName("TG-16 NFR-7: double withdrawal gives an equal snapshot")
  void doubleWithdraw() {
    register(pub("v1", 1), pub("v2", 2, "v1"));
    reg.withdraw(T, v("v2"));
    VersionGraph once = reg.snapshot();
    reg.withdraw(T, v("v2"));
    assertThat(reg.snapshot()).isEqualTo(once).hasSameHashCodeAs(once);
    assertThatThrownBy(() -> reg.withdraw(T, v("nope"))).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("TG-15 NFR-23: 20,000 evaluations run in memory")
  void manyEvaluations() {
    TemplateVersion[] chain = new TemplateVersion[50];
    for (int i = 0; i < 50; i++) {
      chain[i] = i == 0 ? pub("v0", 0) : pub("v" + i, i, "v" + (i - 1));
    }
    register(chain);
    VersionGraph g = reg.snapshot();
    int eligible = 0;
    for (int i = 0; i < 20_000; i++) {
      if (g.eligibleVersion(T, v("v" + (i % 50))) instanceof Eligible) {
        eligible++;
      }
    }
    assertThat(eligible).isEqualTo(20_000 - 20_000 / 50);
  }

  @Test
  @DisplayName("Invariants FR-8: register is idempotent, conflicts and bad edges are rejected")
  void invariants() {
    register(pub("v1", 1), pub("v2", 2, "v1"));
    reg.register(pub("v2", 2, "v1")); // identical: no-op
    assertThatThrownBy(() -> reg.register(pub("v2", 3, "v1")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> reg.register(pub("v3", 3, "missing")))
        .as("unknown parent")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> reg.register(pub("v3", 2, "v2")))
        .as("same time as parent")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> reg.register(pub("v3", 1, "v2")))
        .as("earlier than parent")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> reg.register(pub("v3", 3, "v3")))
        .as("self cycle")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> reg.register(pub("v3", 3, "v1", "v9")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(reg.snapshot().find(T, v("v3"))).isEmpty();
    assertThatThrownBy(() -> reg.publish(T, v("zz"))).isInstanceOf(IllegalArgumentException.class);
    reg.withdraw(T, v("v2"));
    assertThatThrownBy(() -> reg.publish(T, v("v2"))).isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("TemplateVersion copies its parent set defensively")
  void parentsImmutable() {
    Set<com.caseware.fanout.domain.VersionId> ps = new java.util.HashSet<>(List.of(v("v1")));
    TemplateVersion t = new TemplateVersion(T, v("v2"), ps, MAIN, VersionStatus.PUBLISHED, at(2));
    ps.add(v("x"));
    assertThat(t.parents()).containsExactly(v("v1"));
    assertThatThrownBy(() -> t.parents().add(v("y"))).isInstanceOf(UnsupportedOperationException.class);
  }
}
