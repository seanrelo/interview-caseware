package com.caseware.fanout.system;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.fanout.adapter.memory.InMemoryPendingUpdateMarker.Status;
import com.caseware.fanout.domain.ItemState;
import com.caseware.fanout.domain.PublishStatus;
import com.caseware.fanout.fault.CrashingStore;
import com.caseware.fanout.fault.SimulatedCrashException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Regression tests for defects found by T8 and since fixed (DEFECT-1: FR-21/NFR-10, DEFECT-2: FR-20). */
class RegressionTest {

  @Test
  @DisplayName("DEFECT-1 (fixed) FR-21/NFR-10: crash after the DONE compare-and-set but before the progress count must not leave the publish incomplete forever")
  void publishCompletesAfterCrashBetweenCompleteAndCount() {
    CrashingStore[] h = new CrashingStore[1];
    Rig r = new Rig(0, 5, d -> h[0] = new CrashingStore(d, 1, true), 25);
    r.efs(4, 2).version("v7", 10);
    new com.caseware.fanout.app.PublishHandler(Rig.REGION, r.index, r.durable, r.progress, r.clock, 25)
        .handle(r.event("p7", "v7"));
    try {
      r.dispatcher.drain(); // write 1 = lease, write 2 = first complete: applied, ack lost, worker dies
    } catch (SimulatedCrashException expected) {
    }
    h[0].revive();
    r.clock.advance(Rig.LEASE.plusSeconds(1));
    r.settle();

    for (int i = 0; i < 4; i++) {
      assertThat(r.state(r.key(i, "v7"))).isEqualTo(ItemState.DONE);
    }
    PublishStatus s = r.tracker.status("p7");
    assertThat(s.terminal()).as("terminal count %s of total %s", s.terminal(), s.total()).isEqualTo(4);
    assertThat(s.complete()).isTrue();
  }

  @Test
  @DisplayName("DEFECT-2 (fixed) FR-20/ADR 0004: a late event for a withdrawn version must not make it the EF's pending target")
  void withdrawnVersionIsNeverRecorded() {
    Rig r = new Rig().efs(2, 1).version("v5", 100).version("v7", 10);
    r.graph.withdraw(Rig.TPL, Rig.v("v7"));
    r.handler.handle(r.event("p-v5", "v5"));
    r.settle();
    r.handler.handle(r.event("p-v7", "v7")); // delayed event of the now withdrawn version
    r.settle();

    assertThat(r.graph.latestEligible(Rig.TPL)).contains(Rig.v("v5"));
    assertThat(r.real.snapshot(Rig.TPL).values()).containsOnly(new Status(Rig.v("v5"), false));
  }
}
