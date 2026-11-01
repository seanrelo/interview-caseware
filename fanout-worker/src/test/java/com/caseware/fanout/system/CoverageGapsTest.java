package com.caseware.fanout.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.caseware.fanout.adapter.memory.InMemoryPendingUpdateMarker.Status;
import com.caseware.fanout.app.MutableClock;
import com.caseware.fanout.app.PublishHandler;
import com.caseware.fanout.domain.EfId;
import com.caseware.fanout.domain.FirmId;
import com.caseware.fanout.domain.ItemState;
import com.caseware.fanout.domain.WorkKey;
import com.caseware.fanout.fault.SlowIndex;
import com.caseware.fanout.limiter.AdaptiveLimiter;
import com.caseware.fanout.limiter.FirmFairness;
import com.caseware.fanout.limiter.LimiterProperties;
import com.caseware.fanout.app.CallGate;
import com.caseware.fanout.port.DeadLetterSink;
import com.caseware.fanout.port.FirmFlags;
import com.caseware.fanout.port.PendingUpdateMarker;
import com.caseware.fanout.port.PublishProgressStore;
import com.caseware.fanout.port.TemplateVersionIndex;
import com.caseware.fanout.port.VersionGraphReader;
import com.caseware.fanout.port.WorkItemStore;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Matrix rows that no other test covers: FR-14 mid-scan failure, FR-16/NFR-22 spy, FR-17 lost ack, NFR-8 property. */
class CoverageGapsTest {

  @Test
  @DisplayName("U-14: index fails mid-scan; replay of the event completes with no duplicates and no loss")
  void indexFailureMidScanThenReplay() {
    Rig r = new Rig().efs(10, 2).version("v7", 10);
    SlowIndex slow = new SlowIndex(r.index).failOnCall(2);
    PublishHandler h = new PublishHandler(Rig.REGION, slow, r.durable, r.progress, r.clock, 3);

    assertThatThrownBy(() -> h.handle(r.event("p7", "v7"))).isInstanceOf(IllegalStateException.class);
    assertThat(r.tracker.status("p7").sealed()).as("not sealed after a partial scan").isFalse();
    assertThat(r.durable.find(r.key(0, "v7"))).isPresent(); // page 1 survived
    assertThat(r.durable.find(r.key(9, "v7"))).isEmpty();

    slow.failOnCall(-1);
    h.handle(r.event("p7", "v7"));
    assertThat(r.tracker.status("p7").total()).isEqualTo(10);
    assertThat(r.tracker.status("p7").sealed()).isTrue();
    for (int i = 0; i < 10; i++) {
      r.keys.add(r.key(i, "v7"));
    }
    r.settle();
    assertThat(r.tracker.status("p7").complete()).isTrue();
    assertThat(r.tracker.status("p7").done()).isEqualTo(10);
  }

  @Test
  @DisplayName("U-16/NFR-22: no port exposes an EF-load method; the marker port is exactly record + markUnknown + reevaluate")
  void noPortCanLoadAnEf() {
    List<Class<?>> ports =
        List.of(
            PendingUpdateMarker.class, TemplateVersionIndex.class, WorkItemStore.class,
            VersionGraphReader.class, PublishProgressStore.class, DeadLetterSink.class, FirmFlags.class);
    Pattern loadish = Pattern.compile("(?i)(load|open|fetch|read|get|download).*");
    for (Class<?> port : ports) {
      for (Method m : port.getMethods()) {
        assertThat(loadish.matcher(m.getName()).matches())
            .as("%s.%s must not look like an EF load", port.getSimpleName(), m.getName())
            .isFalse();
        assertThat(m.getReturnType()).as("%s.%s returns an EF body?", port.getSimpleName(), m.getName())
            .isNotEqualTo(Object.class);
      }
    }
    assertThat(Arrays.stream(PendingUpdateMarker.class.getMethods()).map(Method::getName).collect(Collectors.toSet()))
        .containsExactlyInAnyOrder("record", "markUnknown", "reevaluate");
    // Domain types carry identifiers only (no content field).
    assertThat(Arrays.stream(com.caseware.fanout.domain.EfRef.class.getRecordComponents()).map(c -> c.getName()))
        .containsExactly("ef", "firm");
  }

  @Test
  @DisplayName("I-17: ack lost after the write applied: retry answers UNCHANGED, final state equal, DONE once")
  void lostAckRetryIsIdempotent() {
    Rig r = new Rig().efs(1, 1).version("v7", 10);
    r.marker.loseAckForNext(1);
    r.handler.handle(r.event("p7", "v7"));
    r.keys.add(r.key(0, "v7"));
    r.settle();

    WorkKey k = r.key(0, "v7");
    assertThat(r.marker.callsFor(k)).isEqualTo(2);
    assertThat(r.marker.delegatedFor(k)).isEqualTo(2);
    assertThat(r.real.status(Rig.ef(0), Rig.TPL)).contains(new Status(Rig.v("v7"), false));
    assertThat(r.state(k)).isEqualTo(ItemState.DONE);
    assertThat(r.tracker.status("p7").done()).isEqualTo(1);
    assertThat(r.dlq.depth()).isZero();
  }

  @Property(tries = 500)
  @Label("C-8 (jqwik): random acquire/release/outcome/time sequences never exceed the cap or the limit at grant; limit stays in [1,max]")
  void limiterNeverExceedsCap(
      @ForAll @IntRange(min = 1, max = 12) int max,
      @ForAll @Size(max = 200) List<@IntRange(min = 0, max = 7) Integer> ops) {
    MutableClock clock = new MutableClock(Rig.T0);
    LimiterProperties p = LimiterProperties.defaults(max);
    AdaptiveLimiter l = new AdaptiveLimiter(p, clock, new FirmFairness(p.firmShare(), Set::of));
    ArrayDeque<CallGate.Permit> held = new ArrayDeque<>();
    int n = 0;
    for (int op : ops) {
      switch (op) {
        case 0, 1, 2, 3 -> {
          int limitBefore = l.limit();
          int inFlightBefore = l.inFlight();
          var permit =
              l.tryAcquire(
                  com.caseware.fanout.domain.WorkItem.pending(
                      new WorkKey(new EfId("e" + n++), Rig.TPL, Rig.v("v1")),
                      new FirmId("f" + (op % 3)), "p", Rig.T0));
          if (permit.isPresent()) {
            assertThat(inFlightBefore).as("granted only below the limit").isLessThan(limitBefore);
            held.add(permit.get());
          }
        }
        case 4 -> { if (!held.isEmpty()) held.poll().release(CallGate.Outcome.SUCCESS); }
        case 5 -> { if (!held.isEmpty()) held.poll().release(CallGate.Outcome.THROTTLED); }
        case 6 -> { if (!held.isEmpty()) held.poll().release(CallGate.Outcome.ERROR); }
        default -> clock.advance(Duration.ofSeconds(20));
      }
      assertThat(l.inFlight()).isEqualTo(held.size());
      assertThat(l.inFlight()).isLessThanOrEqualTo(max);
      assertThat(l.limit()).isBetween(1, max);
    }
  }
}
