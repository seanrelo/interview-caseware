package com.caseware.fanout.graph;

import static com.caseware.fanout.graph.GraphFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.fanout.adapter.memory.InMemoryVersionRegistry;
import com.caseware.fanout.graph.EligibleResult.Eligible;
import com.caseware.fanout.graph.EligibleResult.Unknown;
import com.caseware.fanout.graph.EligibleResult.UpToDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Pins the assumed behaviour of cases where the spec is silent (open points O-1, O-2, O-3). */
class VersionGraphPinningTest {

  private static InMemoryVersionRegistry reg(TemplateVersion... vs) {
    InMemoryVersionRegistry r = new InMemoryVersionRegistry();
    for (TemplateVersion v : vs) {
      r.register(v);
    }
    return r;
  }

  @Test
  @DisplayName("TG-5a FR-11 (O-1): all reachable nodes withdrawn and nothing unreachable gives UpToDate")
  void allReachableWithdrawn() {
    InMemoryVersionRegistry r = reg(pub("v1", 1), pub("v2", 2, "v1"), pub("v3", 3, "v2"));
    r.withdraw(T, v("v2"));
    r.withdraw(T, v("v3"));
    assertThat(r.snapshot().eligibleVersion(T, v("v1"))).isEqualTo(new UpToDate());
  }

  @Test
  @DisplayName("TG-8b FR-12 (O-3): later versions only in another branch give UpToDate")
  void laterOnlyInOtherBranch() {
    InMemoryVersionRegistry r =
        reg(pub("v1", 1), node("v2", 2, CA, VersionStatus.PUBLISHED), node("v3", 3, CA, VersionStatus.PUBLISHED, "v2"));
    assertThat(r.snapshot().eligibleVersion(T, v("v1"), MAIN)).isEqualTo(new UpToDate());
  }

  @Test
  @DisplayName("TG-8c FR-12 (O-1): later withdrawn unreachable nodes still give Unknown")
  void laterWithdrawnUnreachable() {
    InMemoryVersionRegistry r = reg(pub("v1", 1), pub("v2", 2), pub("v3", 3, "v2"));
    r.withdraw(T, v("v2"));
    r.withdraw(T, v("v3"));
    assertThat(r.snapshot().eligibleVersion(T, v("v1"))).isInstanceOf(Unknown.class);
  }

  @Test
  @DisplayName("TG-4b FR-11 NFR-6: incomparable sibling leaves pick by publishedAt then id, insertion order irrelevant")
  void incomparableSiblings() {
    TemplateVersion v1 = pub("v1", 1), v2 = pub("v2", 2, "v1"), v3 = pub("v3", 3, "v1");
    assertThat(reg(v1, v2, v3).snapshot().eligibleVersion(T, v("v1"))).isEqualTo(new Eligible(v("v3")));
    assertThat(reg(v1, v3, v2).snapshot().eligibleVersion(T, v("v1"))).isEqualTo(new Eligible(v("v3")));
    // Id order does not override time: the earlier-published sibling has the greater id.
    TemplateVersion a = pub("v9", 2, "v1"), b = pub("v2", 3, "v1");
    assertThat(reg(v1, a, b).snapshot().eligibleVersion(T, v("v1"))).isEqualTo(new Eligible(v("v2")));
    assertThat(reg(v1, b, a).snapshot().eligibleVersion(T, v("v1"))).isEqualTo(new Eligible(v("v2")));
  }

  @Test
  @DisplayName("TG-4c NFR-6 (O-2 open): equal publishedAt, v9 vs v10 pins the current string order result")
  void equalTimeStringOrder() {
    // O-2 is open: ids compare as strings ("v9" > "v10"); a numeric order would pick v10.
    // If O-2 is resolved to numeric, this expectation must change deliberately.
    TemplateVersion v1 = pub("v1", 1), v9 = pub("v9", 5, "v1"), v10 = pub("v10", 5, "v1");
    assertThat(reg(v1, v9, v10).snapshot().eligibleVersion(T, v("v1"))).isEqualTo(new Eligible(v("v9")));
    assertThat(reg(v1, v10, v9).snapshot().eligibleVersion(T, v("v1"))).isEqualTo(new Eligible(v("v9")));
  }
}
