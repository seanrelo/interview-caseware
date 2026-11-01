package com.caseware.fanout.fault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.caseware.fanout.adapter.memory.InMemoryTemplateVersionIndex;
import com.caseware.fanout.adapter.memory.InMemoryWorkItemStore;
import com.caseware.fanout.domain.EfId;
import com.caseware.fanout.domain.EfRef;
import com.caseware.fanout.domain.FirmId;
import com.caseware.fanout.domain.ItemState;
import com.caseware.fanout.domain.Region;
import com.caseware.fanout.domain.TemplateId;
import com.caseware.fanout.domain.VersionId;
import com.caseware.fanout.domain.WorkItem;
import com.caseware.fanout.domain.WorkKey;
import com.caseware.fanout.port.DownstreamException;
import com.caseware.fanout.port.DownstreamThrottledException;
import com.caseware.fanout.port.MarkResult;
import com.caseware.fanout.port.PendingUpdateMarker;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Self-tests of the fault decorators. They depend only on ports and the in-memory store/index. */
class FaultWrappersTest {

  private static final TemplateId T = new TemplateId("t1");
  private static final FirmId F = new FirmId("f1");
  private static final Instant NOW = Instant.parse("2026-10-30T00:00:00Z");

  private static WorkKey key(String ef) {
    return new WorkKey(new EfId(ef), T, new VersionId("v1"));
  }

  private static WorkItem item(String ef) {
    return WorkItem.pending(key(ef), F, "p1", NOW);
  }

  /** Counts record calls, always APPLIED. */
  private static final class CountingMarker implements PendingUpdateMarker {
    final AtomicInteger records = new AtomicInteger();

    @Override
    public MarkResult record(WorkKey key) {
      records.incrementAndGet();
      return MarkResult.APPLIED;
    }

    @Override
    public void markUnknown(WorkKey key, String internalReason) {}

    @Override
    public MarkResult reevaluate(WorkKey w, com.caseware.fanout.graph.EligibleResult r) {
      return MarkResult.APPLIED;
    }
  }

  @Test
  void flakyMarkerFailsNThenPassesAndCountsPerKey() {
    CountingMarker inner = new CountingMarker();
    FlakyMarker m = new FlakyMarker(inner, 1L).failNext(2);
    WorkKey k = key("a");
    assertThatThrownBy(() -> m.record(k)).isExactlyInstanceOf(DownstreamException.class);
    assertThatThrownBy(() -> m.record(k)).isExactlyInstanceOf(DownstreamException.class);
    assertThat(m.record(k)).isEqualTo(MarkResult.APPLIED);
    assertThat(m.callsFor(k)).isEqualTo(3);
    assertThat(m.delegatedFor(k)).isEqualTo(1);
    assertThat(m.callsFor(key("b"))).isZero();
    assertThat(inner.records).hasValue(1);
  }

  @Test
  void flakyMarkerThrottlesAndThrottleIsADownstreamException() {
    FlakyMarker m = new FlakyMarker(new CountingMarker(), 1L).throttleNext(1);
    assertThatThrownBy(() -> m.record(key("a")))
        .isInstanceOf(DownstreamThrottledException.class)
        .isInstanceOf(DownstreamException.class);
    assertThat(m.record(key("a"))).isEqualTo(MarkResult.APPLIED);
    m.throttleAll(true);
    assertThatThrownBy(() -> m.record(key("a"))).isInstanceOf(DownstreamThrottledException.class);
  }

  @Test
  void flakyMarkerFailAlwaysIsPerKeyAndHealable() {
    FlakyMarker m = new FlakyMarker(new CountingMarker(), 1L).failAlways(key("bad"));
    assertThat(m.record(key("good"))).isEqualTo(MarkResult.APPLIED);
    assertThatThrownBy(() -> m.record(key("bad"))).isInstanceOf(DownstreamException.class);
    m.heal(key("bad"));
    assertThat(m.record(key("bad"))).isEqualTo(MarkResult.APPLIED);
  }

  @Test
  void flakyMarkerLostAckAppliesThenThrows() {
    CountingMarker inner = new CountingMarker();
    FlakyMarker m = new FlakyMarker(inner, 1L).loseAckForNext(1);
    assertThatThrownBy(() -> m.record(key("a"))).isInstanceOf(DownstreamException.class);
    assertThat(inner.records).hasValue(1);
    assertThat(m.delegatedFor(key("a"))).isEqualTo(1);
  }

  @Test
  void flakyMarkerLatencyHookRunsBeforeFailureDecision() {
    List<String> seen = new ArrayList<>();
    FlakyMarker m =
        new FlakyMarker(new CountingMarker(), 1L)
            .failNext(1)
            .latency(k -> seen.add(k.ef().value()));
    assertThatThrownBy(() -> m.record(key("a"))).isInstanceOf(DownstreamException.class);
    assertThat(seen).containsExactly("a");
  }

  @Test
  void flakyMarkerRandomFailuresAreReproducibleForASeed() {
    List<Boolean> first = outcomes(42L);
    List<Boolean> second = outcomes(42L);
    assertThat(first).isEqualTo(second).contains(true, false);
    assertThat(outcomes(7L)).isNotEqualTo(first);
  }

  private static List<Boolean> outcomes(long seed) {
    FlakyMarker m = new FlakyMarker(new CountingMarker(), seed).failureRate(0.5);
    List<Boolean> r = new ArrayList<>();
    for (int i = 0; i < 40; i++) {
      try {
        m.record(key("a"));
        r.add(false);
      } catch (DownstreamException e) {
        r.add(true);
      }
    }
    return r;
  }

  @Test
  void crashingStoreAllowsKWritesThenCrashesAndKeepsDurableState() {
    InMemoryWorkItemStore durable = new InMemoryWorkItemStore();
    CrashingStore s = new CrashingStore(durable, 2);
    assertThat(s.createIfAbsent(item("a"))).isTrue();
    assertThat(s.createIfAbsent(item("b"))).isTrue();
    assertThatThrownBy(() -> s.createIfAbsent(item("c")))
        .isInstanceOf(SimulatedCrashException.class);
    assertThat(s.crashed()).isTrue();
    assertThat(s.find(key("a"))).isPresent(); // reads still work
    assertThat(durable.find(key("c"))).isEmpty();
    assertThat(s.successfulWrites()).isEqualTo(2);
    assertThatThrownBy(() -> s.leaseReady(NOW, Duration.ofSeconds(30), 5, java.util.Set.of()))
        .isInstanceOf(SimulatedCrashException.class);

    s.revive();
    assertThat(s.createIfAbsent(item("c"))).isTrue();
    assertThat(s.createIfAbsent(item("a"))).isFalse(); // survived the "restart"
  }

  @Test
  void crashingStoreApplyThenCrashLosesOnlyTheAck() {
    InMemoryWorkItemStore durable = new InMemoryWorkItemStore();
    CrashingStore s = new CrashingStore(durable, 1, true);
    s.createIfAbsent(item("a"));
    assertThatThrownBy(() -> s.createIfAbsent(item("b")))
        .isInstanceOf(SimulatedCrashException.class);
    assertThat(durable.find(key("b"))).isPresent();
    assertThatThrownBy(() -> s.createIfAbsent(item("c")))
        .isInstanceOf(SimulatedCrashException.class);
    assertThat(durable.find(key("c"))).isEmpty();
  }

  @Test
  void crashingStoreCountsLeaseAndCompleteAsWrites() {
    InMemoryWorkItemStore durable = new InMemoryWorkItemStore();
    durable.createIfAbsent(item("a"));
    CrashingStore s = new CrashingStore(durable, 1);
    List<WorkItem> leased = s.leaseReady(NOW, Duration.ofSeconds(30), 1, java.util.Set.of());
    assertThat(leased).hasSize(1);
    assertThatThrownBy(() -> s.complete(leased.get(0), ItemState.DONE))
        .isInstanceOf(SimulatedCrashException.class);
    assertThat(durable.find(key("a")).orElseThrow().state()).isEqualTo(ItemState.LEASED);
  }

  @Test
  void slowIndexCallsHookAndFailsOnNthCall() {
    InMemoryTemplateVersionIndex idx = new InMemoryTemplateVersionIndex();
    for (int i = 0; i < 5; i++) {
      idx.register(Region.EU, T, new EfRef(new EfId("ef" + i), F));
    }
    List<Integer> hooks = new ArrayList<>();
    SlowIndex s = new SlowIndex(idx).latency(hooks::add).failOnCall(2);
    var p1 = s.findEfsByTemplate(Region.EU, T, Optional.empty(), 2);
    assertThat(p1.items()).hasSize(2);
    assertThatThrownBy(() -> s.findEfsByTemplate(Region.EU, T, p1.nextToken(), 2))
        .isInstanceOf(IllegalStateException.class);
    var p3 = s.findEfsByTemplate(Region.EU, T, p1.nextToken(), 2);
    assertThat(p3.items()).hasSize(2);
    assertThat(hooks).containsExactly(1, 2, 3);
    assertThat(s.calls()).isEqualTo(3);
  }
}
