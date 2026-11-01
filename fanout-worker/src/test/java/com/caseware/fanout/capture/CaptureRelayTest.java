package com.caseware.fanout.capture;

import static com.caseware.fanout.capture.CaptureRig.EU;
import static com.caseware.fanout.capture.CaptureRig.TPL;
import static com.caseware.fanout.capture.CaptureRig.ef;
import static com.caseware.fanout.capture.CaptureRig.t;
import static com.caseware.fanout.capture.CaptureRig.v;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.caseware.fanout.capture.CaptureMetrics.Event;
import com.caseware.fanout.domain.EfRef;
import com.caseware.fanout.domain.Region;
import com.caseware.fanout.domain.TemplateId;
import com.caseware.fanout.domain.VersionId;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CaptureRelayTest {

  /** Store wrapper written for this test: fails on demand and counts calls. */
  static class ToggleStore implements TemplateVersionRecordStore {
    final TemplateVersionRecordStore delegate;
    volatile boolean failing;
    final AtomicInteger calls = new AtomicInteger();

    ToggleStore(TemplateVersionRecordStore delegate) {
      this.delegate = delegate;
    }

    public UpsertResult upsertVersion(
        Region r, EfRef e, TemplateId t, VersionId v, Instant at, CaptureSource s) {
      calls.incrementAndGet();
      if (failing) {
        throw new IllegalStateException("store down");
      }
      return delegate.upsertVersion(r, e, t, v, at, s);
    }

    public UpsertResult upsertBaseline(Region r, EfRef e, TemplateId t, VersionId v, Instant at) {
      calls.incrementAndGet();
      if (failing) {
        throw new IllegalStateException("store down");
      }
      return delegate.upsertBaseline(r, e, t, v, at);
    }

    public Optional<TemplateVersionRecord> find(Region r, EfRef e) {
      return delegate.find(r, e);
    }
  }

  /** Models process death: applies the nth call to the store, then dies before the ack. */
  static final class Crash extends Error {
    Crash() {
      super("simulated process death");
    }
  }

  static final class CrashingStore extends ToggleStore {
    final int crashOnCall;

    CrashingStore(TemplateVersionRecordStore delegate, int crashOnCall) {
      super(delegate);
      this.crashOnCall = crashOnCall;
    }

    @Override
    public UpsertResult upsertVersion(
        Region r, EfRef e, TemplateId t, VersionId v, Instant at, CaptureSource s) {
      UpsertResult res = super.upsertVersion(r, e, t, v, at, s);
      if (calls.get() == crashOnCall) {
        throw new Crash();
      }
      return res;
    }
  }

  private static CaptureRelay.Config cfg(int breaker, Duration timeout) {
    return new CaptureRelay.Config(
        100,
        Duration.ofSeconds(30),
        timeout,
        breaker,
        Duration.ofSeconds(30),
        Duration.ofSeconds(1),
        Duration.ofSeconds(60));
  }

  @Test
  @DisplayName("NFR-30 NFR-32: a failing store nacks with backoff; the capture is applied after recovery")
  void retryAfterStoreFailure() {
    ToggleStore[] holder = new ToggleStore[1];
    CaptureRig rig =
        new CaptureRig(
            s -> holder[0] = new ToggleStore(s), cfg(100, Duration.ofSeconds(2)), 5, o -> o);
    holder[0].failing = true;
    rig.hooks.onCreate(EU, ef("ef-1"), TPL, v("v1"), t(1));
    assertThat(rig.relay.drainOnce()).isZero();
    assertThat(rig.outbox.size()).isEqualTo(1);
    assertThat(rig.relay.drainOnce()).isZero(); // backing off, not visible yet
    assertThat(holder[0].calls).hasValue(1);

    holder[0].failing = false;
    rig.advance(Duration.ofSeconds(2)); // first backoff is <= 1 s
    assertThat(rig.relay.drainOnce()).isEqualTo(1);
    assertThat(rig.store.find(EU, ef("ef-1"))).isPresent();
    assertThat(rig.outbox.size()).isZero();
  }

  @Test
  @DisplayName("NFR-30 TC-12: backoff is exponential, capped and jittered into [delay/2, delay]")
  void backoffShape() {
    CaptureRig rig = new CaptureRig();
    for (int attempt = 0; attempt < 12; attempt++) {
      long cap = Math.min(60_000, 1000L << attempt);
      for (int i = 0; i < 20; i++) {
        long ms = rig.relay.backoff(attempt).toMillis();
        assertThat(ms).isBetween(cap / 2, cap);
      }
    }
  }

  @Test
  @DisplayName("NFR-30: a store call slower than the timeout (fake clock) counts as failed and is retried")
  void timeout() {
    CaptureRig[] rigRef = new CaptureRig[1];
    CaptureRig rig =
        new CaptureRig(
            s ->
                new ToggleStore(s) {
                  @Override
                  public UpsertResult upsertVersion(
                      Region r, EfRef e, TemplateId t, VersionId v, Instant at, CaptureSource src) {
                    UpsertResult res = super.upsertVersion(r, e, t, v, at, src);
                    rigRef[0].advance(Duration.ofSeconds(3)); // the call "took" 3 s
                    return res;
                  }
                },
            cfg(100, Duration.ofSeconds(2)),
            5,
            o -> o);
    rigRef[0] = rig;
    rig.hooks.onCreate(EU, ef("ef-1"), TPL, v("v1"), t(1));
    assertThat(rig.relay.drainOnce()).isZero();
    assertThat(rig.metrics.get(Event.RELAY_TIMEOUT)).isEqualTo(1);
    assertThat(rig.outbox.size()).isEqualTo(1);
    assertThat(rig.store.find(EU, ef("ef-1"))).isPresent(); // applied; the retry is a duplicate
  }

  @Test
  @DisplayName("NFR-30 circuit breaker: opens after N failures, skips while open, probes, closes (fake clock)")
  void circuitBreaker() {
    ToggleStore[] holder = new ToggleStore[1];
    CaptureRig rig =
        new CaptureRig(
            s -> holder[0] = new ToggleStore(s), cfg(3, Duration.ofSeconds(2)), 5, o -> o);
    ToggleStore store = holder[0];
    store.failing = true;
    for (int i = 0; i < 10; i++) {
      rig.hooks.onSave(EU, ef("ef-" + i), TPL, v("v1"), t(i));
    }
    assertThat(rig.relay.drainOnce()).isZero();
    assertThat(store.calls).hasValue(3); // stopped at the threshold, rest left alone
    assertThat(rig.relay.breakerOpen()).isTrue();
    assertThat(rig.metrics.get(Event.BREAKER_OPENED)).isEqualTo(1);

    assertThat(rig.relay.drainOnce()).isZero();
    assertThat(store.calls).hasValue(3); // open: no store call, no claim
    assertThat(rig.metrics.get(Event.BREAKER_SKIPPED)).isEqualTo(1);

    rig.advance(Duration.ofSeconds(31)); // half-open: one probe, still failing
    assertThat(rig.relay.drainOnce()).isZero();
    assertThat(store.calls).hasValue(4);
    assertThat(rig.relay.breakerOpen()).isTrue();
    assertThat(rig.metrics.get(Event.BREAKER_OPENED)).isEqualTo(2);

    store.failing = false;
    rig.advance(Duration.ofSeconds(31)); // probe succeeds and closes it
    assertThat(rig.relay.drainOnce()).isEqualTo(1);
    assertThat(rig.relay.breakerOpen()).isFalse();
    assertThat(rig.drainAll()).isEqualTo(9);
    assertThat(rig.outbox.size()).isZero();
    assertThat(rig.store.scan(EU, Optional.empty())).hasSize(10);
    assertThat(rig.dlq.size()).isZero();
  }

  @Test
  @DisplayName("NFR-29 FR-19: after N failed deliveries the message goes to the dead-letter sink and is kept")
  void deadLetterAfterNAttempts() {
    ToggleStore[] holder = new ToggleStore[1];
    CaptureRig rig =
        new CaptureRig(
            s -> holder[0] = new ToggleStore(s), cfg(1000, Duration.ofSeconds(2)), 3, o -> o);
    holder[0].failing = true;
    rig.hooks.onCreate(EU, ef("ef-1"), TPL, v("v1"), t(1));
    for (int i = 0; i < 3; i++) {
      rig.relay.drainOnce();
      rig.advance(Duration.ofMinutes(2));
    }
    assertThat(rig.outbox.size()).isZero();
    assertThat(rig.dlq.size()).isEqualTo(1);
    var dead = rig.dlq.entries().get(0);
    assertThat(dead.message().attempt()).isEqualTo(3);
    assertThat(dead.message().ef()).isEqualTo(ef("ef-1"));
    assertThat(dead.message().version()).isEqualTo(v("v1"));
    assertThat(rig.metrics.get(Event.DEAD_LETTERED)).isEqualTo(1);
    assertThat(rig.store.find(EU, ef("ef-1"))).isEmpty(); // a failure is not recorded as anything
    assertThat(holder[0].calls).hasValue(3);
  }

  @Test
  @DisplayName("NFR-29: if the dead-letter sink fails the message stays in the outbox, never dropped")
  void failingSinkKeepsMessage() {
    CaptureRig rig = new CaptureRig();
    var failing =
        new com.caseware.fanout.adapter.memory.InMemoryCaptureOutbox(
            rig.clock,
            1,
            10,
            (m, why) -> {
              throw new IllegalStateException("dlq down");
            },
            rig.metrics);
    failing.append(
        new CaptureMessage(
            "m1", CaptureMessage.Kind.VERSION, EU, ef("ef-1"), TPL, v("v1"), t(1), CaptureSource.SAVE, 0));
    failing.claimBatch(1, Duration.ofSeconds(1));
    assertThatThrownBy(() -> failing.nack("m1", Duration.ZERO)).isInstanceOf(IllegalStateException.class);
    assertThat(failing.size()).isEqualTo(1);
  }

  @Test
  @DisplayName("NFR-32 TC-17: relay dies mid-batch after applying; the restarted relay replays with no loss and no duplicates")
  void restartReplays() {
    CrashingStore[] holder = new CrashingStore[1];
    CaptureRig rig =
        new CaptureRig(
            s -> holder[0] = new CrashingStore(s, 4), cfg(100, Duration.ofSeconds(2)), 5, o -> o);
    for (int i = 0; i < 10; i++) {
      rig.hooks.onCreate(EU, ef("ef-" + i), TPL, v("v1"), t(i));
    }
    assertThatThrownBy(() -> rig.relay.drainOnce()).isInstanceOf(Crash.class);
    // calls 1..3 acked; call 4 applied but not acked; 5..10 never reached
    assertThat(rig.outbox.size()).isEqualTo(7);
    assertThat(rig.store.scan(EU, Optional.empty())).hasSize(4);
    assertThat(rig.relay.drainOnce()).isZero(); // all still leased: nothing visible

    // restart: a new relay instance over the same durable outbox and store
    rig.advance(Duration.ofSeconds(31));
    CaptureRelay restarted =
        new CaptureRelay(
            rig.store,
            rig.outbox,
            rig.clock,
            new java.util.SplittableRandom(1),
            rig.metrics,
            cfg(100, Duration.ofSeconds(2)));
    assertThat(restarted.drainOnce()).isEqualTo(7);
    assertThat(rig.outbox.size()).isZero();
    assertThat(rig.store.scan(EU, Optional.empty())).hasSize(10); // no loss, one entry each
    assertThat(rig.metrics.get(UpsertResult.NOOP_DUPLICATE)).isEqualTo(1); // the re-applied 4th
    assertThat(rig.metrics.get(UpsertResult.APPLIED)).isEqualTo(10);
    assertThat(rig.dlq.size()).isZero();
  }

  @Test
  @DisplayName("NFR-32: a lease that expires after a crash does not count as a failed delivery")
  void crashDoesNotBurnAttempts() {
    CaptureRig rig = new CaptureRig();
    rig.hooks.onCreate(EU, ef("ef-1"), TPL, v("v1"), t(1));
    for (int i = 0; i < 20; i++) {
      var batch = rig.outbox.claimBatch(10, Duration.ofSeconds(30));
      assertThat(batch).hasSize(1);
      assertThat(batch.get(0).attempt()).isZero();
      rig.advance(Duration.ofSeconds(31));
    }
    assertThat(rig.dlq.size()).isZero();
  }

  @Test
  @DisplayName("FR-4 NFR-32: an accept with no record is nacked with backoff, never acked, and dead-lettered at the limit")
  void acceptWithoutRecordRetriedThenDeadLettered() {
    CaptureRig rig = new CaptureRig(st -> st, CaptureRelay.Config.defaults(), 3, o -> o);
    rig.hooks.onAccept(EU, ef("ef-9"), TPL, v("v5"), t(1));
    assertThat(rig.relay.drainOnce()).isZero(); // nacked, not acked
    assertThat(rig.outbox.size()).isEqualTo(1);
    assertThat(rig.metrics.get(Event.RELAY_NO_RECORD)).isEqualTo(1);
    assertThat(rig.relay.breakerOpen()).isFalse(); // the store is healthy
    rig.advance(Duration.ofMinutes(5));
    rig.relay.drainOnce();
    rig.advance(Duration.ofMinutes(5));
    rig.relay.drainOnce();
    assertThat(rig.outbox.size()).isZero();
    assertThat(rig.dlq.size()).isEqualTo(1);
    assertThat(rig.dlq.entries().get(0).reason()).contains("no record for accept");
    assertThat(rig.store.find(EU, ef("ef-9"))).isEmpty();
  }
}
