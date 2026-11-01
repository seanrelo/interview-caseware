package com.caseware.fanout.adapter.memory;

import static com.caseware.fanout.graph.GraphFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.caseware.fanout.graph.TemplateVersion;
import com.caseware.fanout.graph.VersionStatus;
import com.caseware.fanout.port.VersionGraphReader;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The graph-backed reader must behave like the stub on the same linear data. */
class GraphBackedVersionGraphReaderContractTest {

  // v1 day1 published, v2 day2 published, v3 day3 withdrawn, v2b day2 published (tie, version order)
  private static VersionGraphReader build(boolean graph) {
    if (graph) {
      InMemoryVersionRegistry reg = new InMemoryVersionRegistry();
      reg.register(pub("v1", 1));
      reg.register(pub("v2", 2, "v1"));
      reg.register(pub("v2b", 2, "v1"));
      reg.register(pub("v3", 3, "v2"));
      reg.withdraw(T, v("v3"));
      return new GraphBackedVersionGraphReader(reg);
    }
    InMemoryVersionGraphReader stub = new InMemoryVersionGraphReader();
    stub.addVersion(T, v("v1"), at(1), true);
    stub.addVersion(T, v("v2"), at(2), true);
    stub.addVersion(T, v("v2b"), at(2), true);
    stub.addVersion(T, v("v3"), at(3), true);
    stub.withdraw(T, v("v3"));
    return stub;
  }

  @ParameterizedTest
  @ValueSource(strings = {"v1", "v2", "v2b", "v3"})
  @DisplayName("FR-8 NFR-27: eligibility and ordering match the stub")
  void sameAsStub(String a) {
    VersionGraphReader g = build(true);
    VersionGraphReader s = build(false);
    assertThat(g.latestEligible(T)).isEqualTo(s.latestEligible(T)).contains(v("v2b"));
    assertThat(g.isEligible(T, v(a))).isEqualTo(s.isEligible(T, v(a)));
    for (String b : List.of("v1", "v2", "v2b", "v3")) {
      assertThat(g.isNewer(T, v(a), v(b))).as("%s newer than %s", a, b).isEqualTo(s.isNewer(T, v(a), v(b)));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  @DisplayName("FR-12: unknown version throws in both implementations")
  void unknownThrows(boolean graph) {
    VersionGraphReader r = build(graph);
    assertThatThrownBy(() -> r.isEligible(T, v("zz"))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> r.isNewer(T, v("zz"), v("v1"))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> r.isNewer(T, v("v1"), v("zz"))).isInstanceOf(IllegalArgumentException.class);
    assertThat(r.latestEligible(new com.caseware.fanout.domain.TemplateId("none"))).isEmpty();
  }
}
