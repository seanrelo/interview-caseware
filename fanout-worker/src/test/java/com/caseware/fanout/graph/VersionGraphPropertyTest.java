package com.caseware.fanout.graph;

import static com.caseware.fanout.graph.GraphFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.caseware.fanout.adapter.memory.InMemoryVersionRegistry;
import com.caseware.fanout.domain.VersionId;
import java.util.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VersionGraphPropertyTest {

  private static final List<MarketBranch> BRANCHES = List.of(MAIN, CA);

  private static List<TemplateVersion> randomDag(Random r) {
    int n = 2 + r.nextInt(25);
    List<TemplateVersion> out = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      Set<String> parents = new TreeSet<>();
      long day = 1;
      for (int j = 0; j < i; j++) {
        if (r.nextInt(100) < 20 || (j == i - 1 && r.nextBoolean())) {
          parents.add("v" + j);
          day = Math.max(day, out.get(j).publishedAt().getEpochSecond() / 86_400 + 1 + r.nextInt(3));
        }
      }
      if (parents.isEmpty()) {
        day = r.nextInt(5);
      }
      VersionStatus st = r.nextInt(4) == 0 ? VersionStatus.WITHDRAWN : VersionStatus.PUBLISHED;
      out.add(node("v" + i, day, BRANCHES.get(r.nextInt(2)), st, parents.toArray(new String[0])));
    }
    return out;
  }

  /** Random topological insertion order. */
  private static List<TemplateVersion> shuffledTopo(List<TemplateVersion> dag, Random r) {
    List<TemplateVersion> pending = new ArrayList<>(dag);
    Set<VersionId> done = new HashSet<>();
    List<TemplateVersion> out = new ArrayList<>();
    while (!pending.isEmpty()) {
      List<TemplateVersion> ready = pending.stream().filter(v -> done.containsAll(v.parents())).toList();
      TemplateVersion pick = ready.get(r.nextInt(ready.size()));
      pending.remove(pick);
      done.add(pick.version());
      out.add(pick);
    }
    return out;
  }

  private static Set<VersionId> ancestors(Map<VersionId, TemplateVersion> by, VersionId of) {
    Set<VersionId> seen = new HashSet<>();
    Deque<VersionId> stack = new ArrayDeque<>(by.get(of).parents());
    while (!stack.isEmpty()) {
      VersionId p = stack.pop();
      if (seen.add(p)) {
        stack.addAll(by.get(p).parents());
      }
    }
    return seen;
  }

  private static VersionGraph build(List<TemplateVersion> order) {
    InMemoryVersionRegistry reg = new InMemoryVersionRegistry();
    order.forEach(reg::register);
    return reg.snapshot();
  }

  // ---- independent reference (does not use TemplateVersion.ORDER or the graph's traversal) ----

  /** Strictly later: greater publishedAt, ties by greater id string. */
  private static boolean later(TemplateVersion x, TemplateVersion y) {
    int c = x.publishedAt().compareTo(y.publishedAt());
    return c > 0 || (c == 0 && x.version().value().compareTo(y.version().value()) > 0);
  }

  /** Reference answer: Eligible(id), "UP_TO_DATE" or "UNKNOWN". */
  private static String oracle(List<TemplateVersion> dag, Map<VersionId, TemplateVersion> by, VersionId base, MarketBranch br) {
    TemplateVersion best = null;
    boolean unreachableLater = false;
    for (TemplateVersion c : dag) {
      if (c.version().equals(base)) {
        continue;
      }
      boolean reach = ancestors(by, c.version()).contains(base);
      if (reach) {
        if (c.status() == VersionStatus.PUBLISHED && c.branch().equals(br) && (best == null || later(c, best))) {
          best = c;
        }
      } else if (c.branch().equals(br) && later(c, by.get(base))) {
        unreachableLater = true;
      }
    }
    if (best != null) {
      return best.version().value();
    }
    return unreachableLater ? "UNKNOWN" : "UP_TO_DATE";
  }

  private static String show(EligibleResult r) {
    return switch (r) {
      case EligibleResult.Eligible e -> e.version().value();
      case EligibleResult.UpToDate u -> "UP_TO_DATE";
      case EligibleResult.Unknown u -> "UNKNOWN";
    };
  }

  private static final int SHUFFLES = 30;

  @Test
  @DisplayName("TG-13 FR-9..FR-12: matches independent oracle, order independent across shuffles")
  void properties() {
    for (long seed = 1; seed <= 200; seed++) {
      Random r = new Random(seed);
      List<TemplateVersion> dag = randomDag(r);
      Map<VersionId, TemplateVersion> by = new HashMap<>();
      dag.forEach(v -> by.put(v.version(), v));
      VersionGraph a = build(dag);
      List<VersionGraph> shuffled = new ArrayList<>();
      for (int k = 0; k < SHUFFLES; k++) {
        VersionGraph b = build(shuffledTopo(dag, new Random(seed * 1000 + k)));
        assertThat(b).as("seed %d shuffle %d", seed, k).isEqualTo(a);
        shuffled.add(b);
      }
      for (TemplateVersion base : dag) {
        for (MarketBranch br : BRANCHES) {
          EligibleResult res = a.eligibleVersion(T, base.version(), br);
          for (VersionGraph b : shuffled) {
            assertThat(b.eligibleVersion(T, base.version(), br)).as("seed %d", seed).isEqualTo(res);
          }
          assertThat(show(res)).as("seed %d base %s branch %s", seed, base.version(), br).isEqualTo(oracle(dag, by, base.version(), br));
          if (res instanceof EligibleResult.Eligible e) {
            TemplateVersion t = by.get(e.version());
            assertThat(t.status()).isEqualTo(VersionStatus.PUBLISHED);
            assertThat(t.branch()).isEqualTo(br);
            assertThat(ancestors(by, t.version())).contains(base.version());
          }
        }
      }
    }
  }

  @Test
  @DisplayName("TG-13 FR-10 I-5: withdrawing w leaves the answer unchanged unless it was Eligible(w)")
  void withdrawInvariant() {
    for (long seed = 1; seed <= 200; seed++) {
      List<TemplateVersion> dag = randomDag(new Random(seed));
      for (TemplateVersion w : dag) {
        if (w.status() == VersionStatus.WITHDRAWN) {
          continue;
        }
        InMemoryVersionRegistry reg = new InMemoryVersionRegistry();
        dag.forEach(reg::register);
        VersionGraph before = reg.snapshot();
        reg.withdraw(T, w.version());
        VersionGraph after = reg.snapshot();
        for (TemplateVersion base : dag) {
          for (MarketBranch br : BRANCHES) {
            EligibleResult prev = before.eligibleVersion(T, base.version(), br);
            if (!prev.equals(new EligibleResult.Eligible(w.version()))) {
              // w was not the selected candidate: the answer is unchanged
              assertThat(after.eligibleVersion(T, base.version(), br))
                  .as("seed %d withdraw %s base %s", seed, w.version(), base.version())
                  .isEqualTo(prev);
            }
          }
        }
      }
    }
  }

  @Test
  @DisplayName("TG-8a FR-12: absent baseline throws, never UpToDate")
  void absentBaseline() {
    for (long seed = 1; seed <= 50; seed++) {
      VersionGraph g = build(randomDag(new Random(seed)));
      for (MarketBranch br : BRANCHES) {
        assertThatThrownBy(() -> g.eligibleVersion(T, v("absent"), br)).isInstanceOf(IllegalArgumentException.class);
      }
      assertThatThrownBy(() -> g.eligibleVersion(T, v("absent"))).isInstanceOf(IllegalArgumentException.class);
    }
  }
}
