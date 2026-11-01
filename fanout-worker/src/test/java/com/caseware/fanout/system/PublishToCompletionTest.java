package com.caseware.fanout.system;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.fanout.adapter.memory.InMemoryPendingUpdateMarker.Status;
import com.caseware.fanout.app.DeadLetterMetrics;
import com.caseware.fanout.domain.FirmId;
import com.caseware.fanout.domain.ItemState;
import com.caseware.fanout.domain.PublishStatus;
import com.caseware.fanout.domain.SkipReason;
import com.caseware.fanout.domain.WorkKey;
import com.caseware.fanout.app.CompletionTracker;
import com.caseware.fanout.limiter.LimiterMetrics;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * End to end: PublishHandler -> store -> Dispatcher -> ItemProcessor (+ VersionSupersessionCheck,
 * RetryingFailureHandler, AdaptiveLimiter) -> CompletionTracker, over the in-memory ports.
 */
class PublishToCompletionTest {

  @Test
  @DisplayName("E2E FR-14..17,20,21 NFR-8,41: v5 partly processed, v7 published, flag-off firm: all publishes complete")
  void happyPathWithSupersessionAndFlagOff() {
    Rig r = new Rig(8, 10, d -> d, 7).efs(40, 4);
    r.flags.setEnabled(Rig.firm(3), false); // 10 EFs of firm-3 are flagged off
    r.version("v5", 100);
    r.handler.handle(r.event("p5", "v5"));
    assertThat(r.tracker.status("p5").total()).isEqualTo(40);

    assertThat(r.dispatcher.runOnce()).isEqualTo(10); // one batch of v5 is processed
    r.clock.advance(Duration.ofSeconds(5));
    r.version("v7", 10);
    r.handler.handle(r.event("p7", "v7"));
    r.handler.handle(r.event("p7", "v7")); // redelivery: no-op
    r.settle();

    PublishStatus p5 = r.tracker.status("p5");
    PublishStatus p7 = r.tracker.status("p7");
    assertThat(p5.complete()).isTrue();
    assertThat(p7.complete()).isTrue();
    assertThat(p7.total()).isEqualTo(40);
    assertThat(p5.done() + p5.skipped()).isEqualTo(40);
    assertThat(p7.done()).isEqualTo(30);
    assertThat(p7.skipped()).isEqualTo(10); // flag-off firm
    assertThat(r.tracker.isCompleteAndClean("p5")).isTrue();
    assertThat(r.tracker.isCompleteAndClean("p7")).isTrue();

    for (int i = 0; i < 40; i++) {
      FirmId f = Rig.firm(i % 4);
      var st = r.real.status(Rig.ef(i), Rig.TPL);
      if (f.equals(Rig.firm(3))) {
        assertThat(st).as("flag-off EF %d untouched", i).isEmpty();
        assertThat(r.durable.find(r.key(i, "v7")).orElseThrow().skipReason()).isEqualTo(SkipReason.FLAG_OFF);
        assertThat(r.marker.callsFor(r.key(i, "v7"))).isZero();
      } else {
        assertThat(st).contains(new Status(Rig.v("v7"), false));
        assertThat(r.marker.callsFor(r.key(i, "v7"))).isEqualTo(1);
        ItemState s5 = r.state(r.key(i, "v5"));
        if (s5 == ItemState.SKIPPED) {
          assertThat(r.durable.find(r.key(i, "v5")).orElseThrow().skipReason())
              .isEqualTo(SkipReason.SUPERSEDED);
          assertThat(r.marker.callsFor(r.key(i, "v5"))).isZero();
        } else {
          assertThat(s5).isEqualTo(ItemState.DONE);
          assertThat(r.marker.callsFor(r.key(i, "v5"))).isEqualTo(1);
        }
      }
    }
    assertThat(r.marker.maxObservedInFlight()).isLessThanOrEqualTo(8);
    assertThat(r.limiter.inFlight()).isZero();
    assertThat(r.registry.get(CompletionTracker.MARKER_LATENCY).timer().count()).isEqualTo(p5.done() + p7.done());
  }

  @Test
  @DisplayName("NFR-38: backlog, in-flight, limit, dlq.depth and publish latency meters exist and read live values")
  void metersWiredEndToEnd() {
    Rig r = new Rig(8, 10, d -> d, 7).efs(10, 2).version("v7", 10);
    LimiterMetrics.register(r.registry, r.limiter, () -> r.durable.firmsWithQueuedWork(r.clock.instant()).size());
    DeadLetterMetrics.bind(r.registry, r.dlq);
    r.marker.failAlways(r.key(0, "v7"));
    r.handler.handle(r.event("p7", "v7"));
    r.settle();
    assertThat(r.registry.get(DeadLetterMetrics.DLQ_DEPTH).gauge().value()).isEqualTo(1.0);
    assertThat(r.registry.get(LimiterMetrics.LIMIT).gauge().value()).isBetween(1.0, 8.0);
    assertThat(r.registry.get(LimiterMetrics.IN_FLIGHT).gauge().value()).isZero();
    assertThat(r.registry.get(LimiterMetrics.BACKLOG).gauge().value()).isZero();
    assertThat(r.registry.get(CompletionTracker.MARKER_LATENCY).timer().count()).isEqualTo(9);
  }

  @Test
  @DisplayName("F-19 e2e: publish -> dispatch -> DLQ: dead-lettered reported separately, never up to date")
  void alwaysFailingItemEndsInDlqAndUnknown() {
    Rig r = new Rig(8, 10, d -> d, 5).efs(20, 4).version("v7", 10);
    WorkKey poison = r.key(7, "v7");
    r.marker.failAlways(poison);
    r.handler.handle(r.event("p7", "v7"));
    r.settle();

    assertThat(r.marker.callsFor(poison)).isEqualTo(5);
    assertThat(r.state(poison)).isEqualTo(ItemState.DEAD_LETTERED);
    assertThat(r.dlq.depth()).isEqualTo(1);
    assertThat(r.dlq.entries().get(0).key()).isEqualTo(poison);
    assertThat(r.dlq.entries().get(0).attempts()).isEqualTo(5);
    assertThat(r.real.status(Rig.ef(7), Rig.TPL)).contains(new Status(Rig.v("v7"), true));
    PublishStatus s = r.tracker.status("p7");
    assertThat(s.complete()).isTrue();
    assertThat(s.deadLettered()).isEqualTo(1);
    assertThat(s.done()).isEqualTo(19);
    assertThat(r.tracker.isCompleteAndClean("p7")).isFalse();
    assertThat(r.real.snapshot(Rig.TPL).values().stream().filter(st -> !st.unknown()).count()).isEqualTo(19);
  }

  @Test
  @DisplayName("F-19 e2e: throttle-exhausted item is dead-lettered like any other failure and shrinks the limit")
  void throttleExhaustedVariant() {
    Rig r = new Rig(8, 10, d -> d, 5).efs(1, 1).version("v7", 10);
    r.marker.throttleNext(5);
    r.handler.handle(r.event("p7", "v7"));
    r.settle();
    assertThat(r.state(r.key(0, "v7"))).isEqualTo(ItemState.DEAD_LETTERED);
    assertThat(r.real.status(Rig.ef(0), Rig.TPL)).contains(new Status(Rig.v("v7"), true));
    assertThat(r.limiter.limit()).isLessThan(8);
    assertThat(r.tracker.isCompleteAndClean("p7")).isFalse();
  }

  @Test
  @DisplayName("F-poison: one poison item among 1,000 -> 999 DONE, 1 dead-lettered, complete but not clean")
  void onePoisonAmongThousand() {
    Rig r = new Rig(16, 100, d -> d, 100).efs(1_000, 20).version("v7", 10);
    WorkKey poison = r.key(500, "v7");
    r.marker.failAlways(poison);
    r.handler.handle(r.event("p7", "v7"));
    r.settle();

    PublishStatus s = r.tracker.status("p7");
    assertThat(s.total()).isEqualTo(1_000);
    assertThat(s.done()).isEqualTo(999);
    assertThat(s.deadLettered()).isEqualTo(1);
    assertThat(s.skipped()).isZero();
    assertThat(s.complete()).isTrue();
    assertThat(r.tracker.isCompleteAndClean("p7")).isFalse();
    assertThat(r.dlq.depth()).isEqualTo(1);
    assertThat(r.real.snapshot(Rig.TPL)).hasSize(1_000);
    assertThat(r.real.snapshot(Rig.TPL).values().stream().filter(Status::unknown).count()).isEqualTo(1);
    assertThat(r.marker.callsFor(poison)).isEqualTo(5);
    assertThat(r.marker.maxObservedInFlight()).isLessThanOrEqualTo(16);
  }

  @Test
  @DisplayName("F-32 e2e (short outage): throttled outage then heal drains everything to DONE, none lost, none dead-lettered")
  void shortOutageThenDrain() {
    Rig r = new Rig(8, 20, d -> d, 50).efs(120, 6).version("v7", 10);
    r.handler.handle(r.event("p7", "v7"));
    r.marker.throttleAll(true);
    for (int round = 0; round < 2; round++) { // 2 failed attempts per item, within the 5 allowed
      r.dispatcher.drain();
      r.clock.advance(Rig.STEP);
      assertThat(r.limiter.limit()).isLessThan(8);
    }
    assertThat(r.real.totalRecordCalls()).as("nothing written during the outage").isZero();
    assertThat(r.tracker.status("p7").terminal()).isZero();
    int limitDuringOutage = r.limiter.limit();

    r.marker.throttleAll(false);
    r.settle();

    PublishStatus s = r.tracker.status("p7");
    assertThat(s.done()).isEqualTo(120);
    assertThat(s.deadLettered()).isZero();
    assertThat(r.dlq.depth()).isZero();
    assertThat(r.tracker.isCompleteAndClean("p7")).isTrue();
    for (int i = 0; i < 120; i++) {
      assertThat(r.marker.delegatedFor(r.key(i, "v7"))).isEqualTo(1);
    }
    assertThat(r.marker.maxObservedInFlight()).isLessThanOrEqualTo(8);
    assertThat(r.limiter.limit()).isGreaterThanOrEqualTo(limitDuringOutage);
    assertThat(r.limiter.inFlight()).isZero();
  }

  @Test
  @DisplayName("F-32 e2e (long outage): items that exhaust retries are DLQ + unknown, distinct from lost; healing does not mark them up to date")
  void longOutageDeadLettersEverythingAndLosesNothing() {
    Rig r = new Rig(8, 20, d -> d, 50).efs(60, 3).version("v7", 10);
    r.handler.handle(r.event("p7", "v7"));
    r.marker.throttleAll(true);
    r.settle(); // five failed attempts per item, then DLQ
    r.marker.throttleAll(false);
    r.settle();

    PublishStatus s = r.tracker.status("p7");
    assertThat(s.total()).isEqualTo(60);
    assertThat(s.deadLettered()).isEqualTo(60);
    assertThat(s.done()).isZero();
    assertThat(s.complete()).as("terminal, nothing in limbo").isTrue();
    assertThat(r.tracker.isCompleteAndClean("p7")).isFalse();
    assertThat(r.dlq.depth()).isEqualTo(60);
    assertThat(r.real.snapshot(Rig.TPL).values()).hasSize(60).allMatch(Status::unknown);
    for (int i = 0; i < 60; i++) {
      assertThat(r.marker.callsFor(r.key(i, "v7"))).isEqualTo(5);
      assertThat(r.state(r.key(i, "v7"))).isEqualTo(ItemState.DEAD_LETTERED);
    }
  }

  @Test
  @DisplayName("NFR-30: downstream fully down: no exception escapes the worker, handler still accepts new events")
  void downstreamDownDoesNotBreakTheWorker() {
    Rig r = new Rig(8, 20, d -> d, 50).efs(30, 3).version("v5", 100);
    r.marker.failureRate(1.0);
    r.handler.handle(r.event("p5", "v5"));
    r.settle();
    r.version("v7", 10);
    r.handler.handle(r.event("p7", "v7")); // still accepted
    r.settle();
    assertThat(r.tracker.status("p5").deadLettered()).isEqualTo(30);
    assertThat(r.tracker.status("p7").total()).isEqualTo(30);
    assertThat(r.tracker.status("p7").skipped() + r.tracker.status("p7").deadLettered()).isEqualTo(30);
    assertThat(r.real.snapshot(Rig.TPL).values()).allMatch(Status::unknown);
  }
}
