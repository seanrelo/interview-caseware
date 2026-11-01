package com.caseware.fanout.system;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.fanout.adapter.memory.InMemoryPendingUpdateMarker.Status;
import com.caseware.fanout.domain.EfId;
import com.caseware.fanout.domain.ItemState;
import com.caseware.fanout.domain.PublishStatus;
import com.caseware.fanout.domain.WorkKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * P-12 (NFR-12, FR-15, FR-17, FR-20, FR-19, NFR-27): duplicate, out-of-order and concurrent publish
 * events end in identical marker state. Versions: v5 < v6a < v6b < v7. v6a and v6b share a publish
 * time and are ordered by the Q3 tie-break (the in-memory graph is a total order, so "incomparable
 * branches" are modelled as equal timestamps). A version enters the graph when its first event
 * arrives (the global layer publishes, then the event is delivered, possibly late).
 */
class OrderIndependenceTest {

  private static final String[] ALL = {"v5", "v6a", "v6b", "v7"};
  private static final Map<String, Long> AGE = Map.of("v5", 100L, "v6a", 50L, "v6b", 50L, "v7", 10L);
  private static final int EFS = 6;

  private static int rank(String v) {
    return Arrays.asList(ALL).indexOf(v);
  }

  private static String highest(List<String> versions) {
    return versions.stream().max(java.util.Comparator.comparingInt(OrderIndependenceTest::rank)).orElseThrow();
  }

  private static Rig rig() {
    return new Rig().efs(EFS, 2);
  }

  /** Delivers one event: publish to the graph on first sight, then handle. */
  private static void deliver(Rig r, String version, String publishId) {
    if (r.graph.latestEligible(Rig.TPL).isEmpty()
        || !knows(r, version)) {
      r.version(version, AGE.get(version));
    }
    r.handler.handle(r.event(publishId, version));
    for (int i = 0; i < EFS; i++) {
      r.keys.add(r.key(i, version));
    }
  }

  private static boolean knows(Rig r, String version) {
    try {
      r.graph.isNewer(Rig.TPL, Rig.v(version), Rig.v(version));
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private static Map<EfId, Status> run(List<String> arrival, boolean[] drainAfter, boolean dupIds) {
    Rig r = rig();
    int dup = 0;
    java.util.Set<String> seen = new java.util.HashSet<>();
    for (int i = 0; i < arrival.size(); i++) {
      String v = arrival.get(i);
      String publishId = "p-" + v;
      if (dupIds && !seen.add(v)) {
        publishId = publishId + "-redelivery-" + dup++; // same version, new publish id
      } else {
        seen.add(v);
      }
      deliver(r, v, publishId);
      if (drainAfter[i % drainAfter.length]) {
        r.dispatcher.drain();
      }
    }
    r.settle();
    assertInvariants(r, arrival);
    return r.real.snapshot(Rig.TPL);
  }

  private static void assertInvariants(Rig r, List<String> arrival) {
    String latest = highest(arrival);
    for (WorkKey k : r.keys) {
      assertThat(r.state(k)).as("every item terminal: %s", k).isIn(ItemState.DONE, ItemState.SKIPPED);
      if (!k.targetVersion().value().equals(latest) && rank(k.targetVersion().value()) < rank(latest)) {
        // older than the latest published: either skipped or processed before the newer arrived,
        // but never left as the final state (checked through the snapshot by the caller)
        assertThat(r.state(k)).isIn(ItemState.DONE, ItemState.SKIPPED);
      }
    }
    r.real.snapshot(Rig.TPL).forEach((ef, st) -> {
      assertThat(st.target().value()).as("no EF ends at an older target: %s", ef).isEqualTo(latest);
      assertThat(st.unknown()).isFalse();
    });
    assertThat(r.real.snapshot(Rig.TPL)).hasSize(EFS);
    // every published version has a completed publish (sealed, all terminal)
    for (String v : arrival) {
      PublishStatus ps = r.tracker.status("p-" + v);
      assertThat(ps.complete()).as("publish p-%s complete", v).isTrue();
    }
  }

  // ---- explicit permutations (cases 1-5, 7)

  static List<List<String>> sequences() {
    List<List<String>> out = new ArrayList<>();
    out.add(List.of("v5", "v7")); // 1
    out.add(List.of("v7", "v5")); // 2 out of order
    out.add(List.of("v5", "v7", "v5")); // 3 duplicate old after new
    out.add(List.of("v7", "v7")); // 4
    out.add(List.of("v5", "v5", "v7", "v7")); // 5
    out.add(List.of("v5", "v7", "v5", "v7")); // 5 interleaved
    out.add(List.of("v7", "v5", "v7", "v5"));
    permutations(new ArrayList<>(List.of("v5", "v6b", "v7")), 0, out); // 7: all 6 permutations
    permutations(new ArrayList<>(List.of("v5", "v6a", "v6b", "v7")), 0, out); // plus tie branch, 24
    return out;
  }

  private static void permutations(List<String> a, int k, List<List<String>> out) {
    if (k == a.size()) {
      out.add(List.copyOf(a));
      return;
    }
    for (int i = k; i < a.size(); i++) {
      java.util.Collections.swap(a, k, i);
      permutations(a, k + 1, out);
      java.util.Collections.swap(a, k, i);
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("sequences")
  @DisplayName("P-12 cases 1-5,7: any arrival order ends with every EF at the latest published version")
  void explicitSequences(List<String> arrival) {
    String expected = highest(arrival);
    for (boolean drainBetween : new boolean[] {false, true}) {
      for (boolean freshIds : new boolean[] {false, true}) {
        Map<EfId, Status> snap = run(arrival, new boolean[] {drainBetween}, freshIds);
        assertThat(snap.values()).containsOnly(new Status(Rig.v(expected), false));
      }
    }
  }

  // ---- case 10: jqwik

  @Provide
  Arbitrary<List<String>> permutation() {
    return Arbitraries.shuffle(ALL);
  }

  @Property(tries = 300)
  @Label("P-12 case 10 (jqwik): permutation + random duplicates + random drain points == canonical")
  void anyPermutationWithDuplicatesEqualsCanonical(
      @ForAll("permutation") List<String> permutation,
      @ForAll @net.jqwik.api.constraints.Size(max = 8) List<@net.jqwik.api.constraints.IntRange(min = 0, max = 3) Integer> extras,
      @ForAll long seed) {
    SplittableRandom rnd = new SplittableRandom(seed);
    List<String> arrival = new ArrayList<>(permutation);
    for (int e : extras) {
      arrival.add(rnd.nextInt(arrival.size() + 1), ALL[e]);
    }
    boolean[] drain = new boolean[arrival.size()];
    for (int i = 0; i < drain.length; i++) {
      drain[i] = rnd.nextBoolean();
    }
    Map<EfId, Status> canonical = run(List.of(ALL), new boolean[] {false}, false);
    Map<EfId, Status> actual = run(arrival, drain, rnd.nextBoolean());
    assertThat(actual).as("arrival=%s drain=%s seed=%d", arrival, Arrays.toString(drain), seed).isEqualTo(canonical);
  }

  @Property(tries = 300)
  @Label("P-12 + FR-19 (jqwik): dead letters never end 'up to date', never overwrite a newer target")
  void deadLettersAreOrderIndependent(
      @ForAll("permutation") List<String> permutation,
      @ForAll @net.jqwik.api.constraints.Size(max = 6) List<@net.jqwik.api.constraints.IntRange(min = 0, max = 3) Integer> extras,
      @ForAll long seed) {
    SplittableRandom rnd = new SplittableRandom(seed);
    List<String> arrival = new ArrayList<>(permutation);
    for (int e : extras) {
      arrival.add(rnd.nextInt(arrival.size() + 1), ALL[e]);
    }
    Rig r = rig();
    r.marker.failAlways(r.key(0, "v5")); // old version of ef 0 can never be written
    r.marker.failAlways(r.key(1, "v7")); // latest version of ef 1 can never be written
    for (String v : arrival) {
      deliver(r, v, "p-" + v);
      if (rnd.nextBoolean()) {
        r.dispatcher.drain();
        r.clock.advance(Rig.STEP);
      }
    }
    r.settle();

    String ctx = "arrival=" + arrival + " seed=" + seed;
    Map<EfId, Status> snap = r.real.snapshot(Rig.TPL);
    assertThat(snap.get(Rig.ef(0))).as("ef0 " + ctx).isEqualTo(new Status(Rig.v("v7"), false));
    assertThat(snap.get(Rig.ef(1))).as("ef1 is unknown, not up to date " + ctx).isEqualTo(new Status(Rig.v("v7"), true));
    for (int i = 2; i < EFS; i++) {
      assertThat(snap.get(Rig.ef(i))).as("ef" + i + " " + ctx).isEqualTo(new Status(Rig.v("v7"), false));
    }
    assertThat(r.state(r.key(1, "v7"))).isEqualTo(ItemState.DEAD_LETTERED);
    assertThat(r.dlq.entries().stream().filter(d -> d.key().equals(r.key(1, "v7"))).count())
        .as("ef1 v7 dead-lettered exactly once " + ctx)
        .isEqualTo(1);
    assertThat(r.tracker.status("p-v7").deadLettered()).isEqualTo(1);
    assertThat(r.tracker.isCompleteAndClean("p-v7")).isFalse();
    assertThat(r.tracker.isComplete("p-v7")).isTrue();
  }

  // ---- case 6: concurrency

  @Test
  @DisplayName("P-12 case 6: v5 and v7 handled and dispatched concurrently on one store")
  void concurrentPublishesOfDifferentVersions() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      for (int iter = 0; iter < 100; iter++) {
        Rig r = rig();
        r.version("v5", 100).version("v7", 10); // both published; only event arrival races
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> fs = new ArrayList<>();
        for (String v : List.of("v5", "v7", "v7", "v5")) {
          fs.add(
              pool.submit(
                  () -> {
                    go.await();
                    r.handler.handle(r.event("p-" + v, v));
                    r.dispatcher.drain();
                    return null;
                  }));
        }
        go.countDown();
        for (Future<?> f : fs) {
          f.get(30, TimeUnit.SECONDS);
        }
        r.settle();
        assertThat(r.real.snapshot(Rig.TPL).values())
            .as("iteration %d", iter)
            .hasSize(EFS)
            .containsOnly(new Status(Rig.v("v7"), false));
        assertThat(r.tracker.status("p-v5").complete()).isTrue();
        assertThat(r.tracker.status("p-v7").complete()).isTrue();
        assertThat(r.tracker.status("p-v7").total()).isEqualTo(EFS);
        for (int i = 0; i < EFS; i++) {
          assertThat(r.marker.delegatedFor(r.key(i, "v7"))).isEqualTo(1);
        }
      }
    } finally {
      pool.shutdownNow();
    }
  }

  // ---- case 8 (documented behaviour) and case 9

  @Test
  @DisplayName("P-12 case 8: v7 withdrawn, v5 event processed: final state is the graph's latestEligible")
  void withdrawnLatestFallsBack() {
    Rig r = rig().version("v5", 100).version("v7", 10);
    r.graph.withdraw(Rig.TPL, Rig.v("v7"));
    r.handler.handle(r.event("p-v5", "v5"));
    r.settle();
    assertThat(r.graph.latestEligible(Rig.TPL)).contains(Rig.v("v5"));
    assertThat(r.real.snapshot(Rig.TPL).values()).hasSize(EFS).containsOnly(new Status(Rig.v("v5"), false));
  }

  @Test
  @DisplayName("P-12 case 9 / ADR 0006 c2: v5 dead-lettered after v7 was recorded does not overwrite v7")
  void staleDeadLetterDoesNotOverwriteNewerTarget() {
    Rig r = new Rig().efs(1, 1).version("v5", 100);
    WorkKey v5 = r.key(0, "v5");
    WorkKey v7 = r.key(0, "v7");
    r.marker.failAlways(v5);
    boolean[] raced = new boolean[1];
    // On the 5th (last) attempt, while v5 is in its downstream call, another worker finishes v7.
    r.marker.latency(
        k -> {
          if (k.equals(v5) && r.marker.callsFor(v5) == 5 && !raced[0]) {
            raced[0] = true;
            r.graph.addVersion(Rig.TPL, Rig.v("v7"), Rig.T0.minusSeconds(10), true);
            r.real.record(v7);
          }
        });
    r.handler.handle(r.event("p-v5", "v5"));
    r.settle();

    assertThat(raced[0]).isTrue();
    assertThat(r.marker.callsFor(v5)).isEqualTo(5);
    assertThat(r.state(v5)).isEqualTo(ItemState.DEAD_LETTERED);
    assertThat(r.dlq.depth()).isEqualTo(1);
    assertThat(r.real.status(Rig.ef(0), Rig.TPL)).contains(new Status(Rig.v("v7"), false));
    assertThat(r.tracker.isCompleteAndClean("p-v5")).isFalse(); // reported, even though EF shows v7

    // and the reverse: a v7 event after v5-unknown ends v7 pending
    Rig q = new Rig().efs(1, 1).version("v5", 100);
    q.marker.failAlways(q.key(0, "v5"));
    q.handler.handle(q.event("p-v5", "v5"));
    q.settle();
    assertThat(q.real.status(Rig.ef(0), Rig.TPL)).contains(new Status(Rig.v("v5"), true));
    q.version("v7", 10);
    q.handler.handle(q.event("p-v7", "v7"));
    q.settle();
    assertThat(q.real.status(Rig.ef(0), Rig.TPL)).contains(new Status(Rig.v("v7"), false));
  }
}
