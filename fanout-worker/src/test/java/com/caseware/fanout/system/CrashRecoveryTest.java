package com.caseware.fanout.system;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.fanout.app.Dispatcher;
import com.caseware.fanout.domain.ItemState;
import com.caseware.fanout.domain.WorkKey;
import com.caseware.fanout.fault.CrashingStore;
import com.caseware.fanout.fault.SimulatedCrashException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * F-10 (NFR-10): a worker dies after k store writes (leaseReady counts as a write), a new worker
 * resumes on the same durable store once the leases expire.
 */
class CrashRecoveryTest {

  private static final int N = 12;
  private static final int BATCH = 5;

  static Stream<Arguments> crashPoints() {
    // Writes in a clean run: 3 leases + 12 completes + 1 empty lease = 16. Go past the end too.
    return IntStream.rangeClosed(0, 20)
        .boxed()
        .flatMap(k -> Stream.of(Arguments.of(k, false), Arguments.of(k, true)));
  }

  private static Rig rigWith(CrashingStore[] holder, long k, boolean applyThenCrash) {
    return new Rig(
        0,
        BATCH,
        durable -> holder[0] = new CrashingStore(durable, k, applyThenCrash),
        25);
  }

  private static void runUntilCrashOrQuiet(Dispatcher d) {
    try {
      d.drain();
    } catch (SimulatedCrashException expected) {
      // process died
    }
  }

  @ParameterizedTest(name = "crash after {0} writes, applyThenCrash={1}")
  @MethodSource("crashPoints")
  @DisplayName("F-10: crash mid-batch then restart: nothing lost, no DONE item re-invoked, exact call counts")
  void crashThenRestart(long k, boolean applyThenCrash) {
    CrashingStore[] h = new CrashingStore[1];
    Rig r = rigWith(h, k, applyThenCrash);
    r.efs(N, 3).version("v7", 10);
    // Publish through a healthy path: item creation is not what is under test.
    new com.caseware.fanout.app.PublishHandler(
            Rig.REGION, r.index, r.durable, r.progress, r.clock, 25)
        .handle(r.event("p7", "v7"));
    List<WorkKey> keys = new ArrayList<>();
    for (int i = 0; i < N; i++) {
      keys.add(r.key(i, "v7"));
    }

    runUntilCrashOrQuiet(r.dispatcher);

    Set<WorkKey> doneAtCrash = new HashSet<>();
    Map<WorkKey, Integer> callsAtCrash = new HashMap<>();
    for (WorkKey key : keys) {
      if (r.state(key) == ItemState.DONE) {
        doneAtCrash.add(key);
      }
      callsAtCrash.put(key, r.marker.callsFor(key));
    }

    // Restart: same durable state, fresh budget, leases of the dead worker expire.
    h[0].revive();
    r.clock.advance(Rig.LEASE.plusSeconds(1));
    r.settle();

    int reinvoked = 0;
    for (WorkKey key : keys) {
      assertThat(r.state(key)).as("no item lost: %s", key).isEqualTo(ItemState.DONE);
      int expected;
      if (doneAtCrash.contains(key)) {
        expected = 1; // completed before the crash: never re-invoked
        assertThat(r.marker.callsFor(key)).as("DONE item re-invoked: %s", key).isEqualTo(1);
      } else {
        // not completed at the crash: invoked once more if it had already reached the marker
        expected = callsAtCrash.get(key) + 1;
        if (callsAtCrash.get(key) > 0) {
          reinvoked++;
        }
      }
      assertThat(r.marker.delegatedFor(key)).as("marker writes for %s", key).isEqualTo(expected);
    }
    // At most one item can be between "marker written" and "complete" when the worker dies.
    assertThat(reinvoked).isLessThanOrEqualTo(1);
    assertThat(r.marker.totalCalls()).isEqualTo(N + reinvoked);
    assertThat(r.real.totalRecordCalls()).isEqualTo(N + reinvoked);
    assertThat(r.real.snapshot(Rig.TPL).values()).hasSize(N).allMatch(s -> !s.unknown());
  }

  @Test
  @DisplayName("F-10: repeated crashes with small write budgets still converge, one write per key")
  void repeatedCrashes() {
    CrashingStore[] h = new CrashingStore[1];
    Rig r = rigWith(h, 2, false);
    r.efs(N, 3).version("v7", 10);
    new com.caseware.fanout.app.PublishHandler(
            Rig.REGION, r.index, r.durable, r.progress, r.clock, 25)
        .handle(r.event("p7", "v7"));
    runUntilCrashOrQuiet(r.dispatcher);
    for (long budget : new long[] {3, 1, 4, 2, 6}) {
      h[0].revive(budget);
      r.clock.advance(Rig.LEASE.plusSeconds(1));
      runUntilCrashOrQuiet(r.dispatcher);
    }
    h[0].revive();
    r.clock.advance(Rig.LEASE.plusSeconds(1));
    r.settle();

    for (int i = 0; i < N; i++) {
      WorkKey k = r.key(i, "v7");
      assertThat(r.state(k)).isEqualTo(ItemState.DONE);
      assertThat(r.marker.delegatedFor(k)).isBetween(1, 7); // at most one re-invocation per crash (6 crashes)
    }
    assertThat(r.real.snapshot(Rig.TPL)).hasSize(N);
    assertThat(r.durable.firmsWithQueuedWork(r.clock.instant())).isEmpty();
  }
}
