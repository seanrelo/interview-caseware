package com.caseware.fanout.capture;

import static com.caseware.fanout.capture.CaptureRig.EU;
import static com.caseware.fanout.capture.CaptureRig.OTHER;
import static com.caseware.fanout.capture.CaptureRig.TPL;
import static com.caseware.fanout.capture.CaptureRig.ef;
import static com.caseware.fanout.capture.CaptureRig.firm;
import static com.caseware.fanout.capture.CaptureRig.t;
import static com.caseware.fanout.capture.CaptureRig.v;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.caseware.fanout.capture.CaptureMetrics.Event;
import com.caseware.fanout.domain.EfRef;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CaptureHooksTest {

  private final CaptureRig rig = new CaptureRig();
  private final EfRef ef = ef("ef-1");

  private TemplateVersionRecord rec() {
    return rig.store.find(EU, ef).orElseThrow();
  }

  @Test
  @DisplayName("FR-1 TC-1: create is captured through hook and relay")
  void create() {
    rig.hooks.onCreate(EU, ef, TPL, v("v1"), t(1));
    assertThat(rig.store.find(EU, ef)).isEmpty(); // async: not before the relay
    assertThat(rig.drainAll()).isEqualTo(1);
    assertThat(rec().version()).isEqualTo(v("v1"));
    assertThat(rec().source()).isEqualTo(CaptureSource.CREATE);
    assertThat(rig.outbox.size()).isZero();
  }

  @Test
  @DisplayName("FR-2 TC-2: open is captured through hook and relay")
  void open() {
    rig.hooks.onCreate(EU, ef, TPL, v("v1"), t(1));
    rig.hooks.onOpen(EU, ef, TPL, v("v2"), t(2));
    rig.drainAll();
    assertThat(rec().version()).isEqualTo(v("v2"));
    assertThat(rec().source()).isEqualTo(CaptureSource.OPEN);
  }

  @Test
  @DisplayName("FR-3 TC-3: save is captured through hook and relay")
  void save() {
    rig.hooks.onCreate(EU, ef, TPL, v("v1"), t(1));
    rig.hooks.onSave(EU, ef, TPL, v("v3"), t(3));
    rig.drainAll();
    assertThat(rec().version()).isEqualTo(v("v3"));
    assertThat(rec().source()).isEqualTo(CaptureSource.SAVE);
  }

  @Test
  @DisplayName("FR-4 TC-4: accept through hook and relay sets the baseline only")
  void accept() {
    rig.hooks.onCreate(EU, ef, TPL, v("v1"), t(1));
    rig.drainAll();
    rig.hooks.onAccept(EU, ef, TPL, v("v5"), t(9));
    rig.drainAll();
    assertThat(rec().baseline()).contains(v("v5"));
    assertThat(rec().version()).isEqualTo(v("v1"));
    assertThat(rec().source()).isEqualTo(CaptureSource.CREATE); // ACCEPT is never stored
  }

  @Test
  @DisplayName("FR-5 TC-5: the same capture delivered twice keeps exactly one entry")
  void duplicateDelivery() {
    rig.hooks.onSave(EU, ef, TPL, v("v1"), t(1));
    rig.hooks.onSave(EU, ef, TPL, v("v1"), t(1));
    assertThat(rig.drainAll()).isEqualTo(2);
    assertThat(rig.store.scan(EU, Optional.empty())).hasSize(1);
    assertThat(rig.metrics.get(UpsertResult.NOOP_DUPLICATE)).isEqualTo(1);
  }

  @Test
  @DisplayName("FR-5 NFR-27 TC-6: out-of-order delivery keeps the newer capture")
  void outOfOrder() {
    rig.hooks.onSave(EU, ef, TPL, v("v2"), t(20));
    rig.hooks.onSave(EU, ef, TPL, v("v1"), t(10));
    rig.drainAll();
    assertThat(rec().version()).isEqualTo(v("v2"));
    assertThat(rig.metrics.get(UpsertResult.NOOP_STALE)).isEqualTo(1);
  }

  @Test
  @DisplayName("NFR-27: the same set of events in any order gives the same final record")
  void orderIndependent() {
    var a = new CaptureRig();
    var b = new CaptureRig();
    a.hooks.onCreate(EU, ef, TPL, v("v1"), t(1));
    a.hooks.onSave(EU, ef, TPL, v("v2"), t(2));
    a.hooks.onAccept(EU, ef, TPL, v("v1"), t(3));
    a.hooks.onOpen(EU, ef, TPL, v("v3"), t(4));
    a.drainAll();
    // reverse arrival, accept drained last so the record exists
    b.hooks.onOpen(EU, ef, TPL, v("v3"), t(4));
    b.hooks.onSave(EU, ef, TPL, v("v2"), t(2));
    b.hooks.onCreate(EU, ef, TPL, v("v1"), t(1));
    b.drainAll();
    b.hooks.onAccept(EU, ef, TPL, v("v1"), t(3));
    b.drainAll();
    assertThat(b.store.find(EU, ef).orElseThrow().version()).isEqualTo(v("v3"));
    assertThat(b.store.find(EU, ef).orElseThrow().baseline())
        .isEqualTo(a.store.find(EU, ef).orElseThrow().baseline());
    assertThat(b.store.find(EU, ef).orElseThrow().versionCapturedAt())
        .isEqualTo(a.store.find(EU, ef).orElseThrow().versionCapturedAt());
  }

  @Test
  @DisplayName("NFR-1 TC-10: no capture port has a load method, and the hook performs zero loads")
  void noLoadAnywhere() {
    for (Class<?> c :
        List.of(
            CaptureHook.class,
            CaptureHooks.class,
            CaptureOutbox.class,
            CaptureRelay.class,
            CaptureDeadLetterSink.class,
            TemplateVersionRecordStore.class,
            TemplateVersionRecordScan.class)) {
      for (Method m : c.getDeclaredMethods()) {
        assertThat(m.getName().toLowerCase()).as("%s.%s", c.getSimpleName(), m.getName()).doesNotContain("load");
      }
      // the hook and relay constructors take no collaborator that could load an EF
      for (var ctor : c.getDeclaredConstructors()) {
        for (Class<?> p : ctor.getParameterTypes()) {
          for (Method m : p.getMethods()) {
            assertThat(m.getName().toLowerCase()).as("%s param %s", c.getSimpleName(), p).doesNotContain("load");
          }
          assertThat(EfLoader.class.isAssignableFrom(p) || EfCatalog.class.isAssignableFrom(p))
              .as("%s ctor param %s must not be an EfLoader or EfCatalog", c.getSimpleName(), p)
              .isFalse();
        }
      }
    }
    // the structural check above is the proof (no constructor can receive a loader); this run is a smoke test
    rig.hooks.onCreate(EU, ef, TPL, v("v1"), t(1));
    rig.hooks.onOpen(EU, ef, TPL, v("v1"), t(2));
    rig.hooks.onSave(EU, ef, TPL, v("v1"), t(3));
    rig.hooks.onAccept(EU, ef, TPL, v("v1"), t(4));
    rig.drainAll();
    assertThat(Modifier.isFinal(CaptureHooks.class.getModifiers())).isTrue();
  }

  @Test
  @DisplayName("NFR-2 TC-11: hook cost is one local append (timing is indicative only, not asserted)")
  void timingIndicative() {
    int n = 500;
    long[] ns = new long[n];
    for (int i = 0; i < n; i++) {
      EfRef e = ef("ef-" + i);
      long s = System.nanoTime();
      rig.hooks.onSave(EU, e, TPL, v("v1"), t(i));
      ns[i] = System.nanoTime() - s;
    }
    java.util.Arrays.sort(ns);
    System.out.printf("TC-11 indicative: hook p95 = %.3f ms over %d calls%n", ns[(int) (n * 0.95)] / 1e6, n);
    assertThat(rig.outbox.size()).isEqualTo(n); // exactly one append per call
  }

  @Test
  @DisplayName("NFR-3 TC-12: outbox down: the hook never throws, the capture waits in memory and is applied later")
  void outboxDown() {
    boolean[] down = {true};
    CaptureOutbox flaky =
        new CaptureOutbox() {
          public void append(CaptureMessage m) {
            if (down[0]) {
              throw new IllegalStateException("outbox down");
            }
          }

          public List<CaptureMessage> claimBatch(int max, Duration lease) {
            return List.of();
          }

          public void ack(String id) {}

          public void nack(String id, Duration delay) {}
        };
    CaptureHooks hooks = new CaptureHooks(rig.flags, flaky, rig.metrics, 10);
    assertThatCode(() -> hooks.onCreate(EU, ef, TPL, v("v1"), t(1))).doesNotThrowAnyException();
    assertThat(hooks.fallbackSize()).isEqualTo(1);
    assertThat(rig.metrics.get(Event.HOOK_FAILED)).isEqualTo(1);
    assertThat(hooks.retryFallback()).isZero(); // still down: stays queued
    assertThat(hooks.fallbackSize()).isEqualTo(1);

    down[0] = false;
    assertThat(hooks.retryFallback()).isEqualTo(1);
    assertThat(hooks.fallbackSize()).isZero();
  }

  @Test
  @DisplayName("NFR-3 TC-12: a fallback queue replays into the real outbox and the relay applies it")
  void fallbackReplaysIntoOutbox() {
    CaptureRig r = new CaptureRig();
    boolean[] down = {true};
    CaptureOutbox gate =
        new CaptureOutbox() {
          public void append(CaptureMessage m) {
            if (down[0]) {
              throw new IllegalStateException("outbox down");
            }
            r.outbox.append(m);
          }

          public List<CaptureMessage> claimBatch(int max, Duration lease) {
            return r.outbox.claimBatch(max, lease);
          }

          public void ack(String id) {
            r.outbox.ack(id);
          }

          public void nack(String id, Duration delay) {
            r.outbox.nack(id, delay);
          }
        };
    CaptureHooks hooks = new CaptureHooks(r.flags, gate, r.metrics, 10);
    hooks.onCreate(EU, ef, TPL, v("v1"), t(1)); // the EF operation proceeds, nothing thrown
    hooks.onSave(EU, ef, TPL, v("v2"), t(2));
    assertThat(hooks.fallbackSize()).isEqualTo(2);
    assertThat(r.drainAll()).isZero();
    down[0] = false;
    assertThat(hooks.retryFallback()).isEqualTo(2);
    r.drainAll();
    assertThat(r.store.find(EU, ef).orElseThrow().version()).isEqualTo(v("v2"));
  }

  @Test
  @DisplayName("NFR-3: bad input and a throwing flag lookup are swallowed and counted; fallback is bounded")
  void neverThrows() {
    assertThatCode(() -> rig.hooks.onSave(EU, ef, null, v("v1"), t(1))).doesNotThrowAnyException();
    assertThatCode(() -> rig.hooks.onSave(EU, null, TPL, v("v1"), t(1))).doesNotThrowAnyException();
    assertThat(rig.metrics.get(Event.HOOK_FAILED)).isEqualTo(2);

    CaptureHooks throwingFlags =
        new CaptureHooks(
            f -> {
              throw new IllegalStateException("flag service down");
            },
            rig.outbox,
            rig.metrics,
            2);
    assertThatCode(() -> throwingFlags.onSave(EU, ef, TPL, v("v1"), t(1))).doesNotThrowAnyException();
    assertThat(rig.outbox.size()).isZero(); // fail closed

    CaptureOutbox broken =
        new CaptureOutbox() {
          public void append(CaptureMessage m) {
            throw new IllegalStateException("down");
          }

          public List<CaptureMessage> claimBatch(int max, Duration lease) {
            return List.of();
          }

          public void ack(String id) {}

          public void nack(String id, Duration delay) {}
        };
    CaptureHooks bounded = new CaptureHooks(rig.flags, broken, rig.metrics, 2);
    for (int i = 0; i < 5; i++) {
      bounded.onSave(EU, ef, TPL, v("v" + i), t(i));
    }
    assertThat(bounded.fallbackSize()).isEqualTo(2);
    assertThat(rig.metrics.get(Event.FALLBACK_DROPPED)).isEqualTo(3);
  }

  @Test
  @DisplayName("NFR-41 TC-18: flag off for the firm: no outbox row, no record, counted")
  void flagOff() {
    rig.flags.setEnabled(firm(1), false);
    rig.hooks.onCreate(EU, ef, TPL, v("v1"), t(1));
    rig.hooks.onOpen(EU, ef, TPL, v("v1"), t(2));
    rig.hooks.onSave(EU, ef, TPL, v("v1"), t(3));
    rig.hooks.onAccept(EU, ef, TPL, v("v1"), t(4));
    assertThat(rig.outbox.size()).isZero();
    assertThat(rig.drainAll()).isZero();
    assertThat(rig.store.find(EU, ef)).isEmpty();
    assertThat(rig.metrics.get(Event.HOOK_FLAG_OFF)).isEqualTo(4);
    // another firm is unaffected
    rig.hooks.onCreate(EU, ef("ef-2", 2), TPL, v("v1"), t(1));
    assertThat(rig.outbox.size()).isEqualTo(1);
  }

  @Test
  @DisplayName("NFR-42 TC-19: stored records are inert while off; re-enabling resumes capture")
  void reEnableResumes() {
    rig.hooks.onCreate(EU, ef, TPL, v("v1"), t(1));
    rig.drainAll();
    TemplateVersionRecord before = rec();

    rig.flags.setEnabled(firm(1), false);
    rig.hooks.onSave(EU, ef, OTHER, v("w9"), t(50)); // dropped
    rig.drainAll();
    assertThat(rec()).isEqualTo(before); // untouched, no migration, nothing deleted

    rig.flags.setEnabled(firm(1), true);
    rig.hooks.onSave(EU, ef, TPL, v("v2"), t(60));
    rig.drainAll();
    assertThat(rec().version()).isEqualTo(v("v2"));
  }
}
