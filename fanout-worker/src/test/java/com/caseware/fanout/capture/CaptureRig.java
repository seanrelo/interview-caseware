package com.caseware.fanout.capture;

import com.caseware.fanout.adapter.memory.InMemoryCaptureDeadLetterSink;
import com.caseware.fanout.adapter.memory.InMemoryCaptureOutbox;
import com.caseware.fanout.adapter.memory.InMemoryFirmFlags;
import com.caseware.fanout.adapter.memory.InMemoryTemplateVersionRecordStore;
import com.caseware.fanout.app.MutableClock;
import com.caseware.fanout.domain.EfId;
import com.caseware.fanout.domain.EfRef;
import com.caseware.fanout.domain.FirmId;
import com.caseware.fanout.domain.Region;
import com.caseware.fanout.domain.TemplateId;
import com.caseware.fanout.domain.VersionId;
import java.time.Duration;
import java.time.Instant;
import java.util.SplittableRandom;

/** Hooks + outbox + relay + store over in-memory adapters and a fake clock. Nothing sleeps. */
final class CaptureRig {

  static final Instant T0 = Instant.parse("2026-10-30T10:00:00Z");
  static final Region EU = Region.EU;
  static final TemplateId TPL = new TemplateId("tpl");
  static final TemplateId OTHER = new TemplateId("other");

  final MutableClock clock = new MutableClock(T0);
  final InMemoryFirmFlags flags = new InMemoryFirmFlags();
  final CaptureMetrics metrics = new CaptureMetrics();
  final InMemoryTemplateVersionRecordStore store = new InMemoryTemplateVersionRecordStore(metrics);
  final InMemoryCaptureDeadLetterSink dlq = new InMemoryCaptureDeadLetterSink(clock);
  final InMemoryCaptureOutbox outbox;
  final CaptureRelay relay;
  final CaptureHooks hooks;

  CaptureRig() {
    this(store -> store, CaptureRelay.Config.defaults(), 5, o -> o);
  }

  CaptureRig(
      java.util.function.UnaryOperator<TemplateVersionRecordStore> storeWrap,
      CaptureRelay.Config cfg,
      int maxAttempts,
      java.util.function.UnaryOperator<CaptureOutbox> outboxWrap) {
    this.outbox = new InMemoryCaptureOutbox(clock, maxAttempts, 1000, dlq, metrics);
    this.relay =
        new CaptureRelay(
            storeWrap.apply(store), outbox, clock, new SplittableRandom(3), metrics, cfg);
    this.hooks = new CaptureHooks(flags, outboxWrap.apply(outbox), metrics, 100);
  }

  static EfRef ef(String id) {
    return new EfRef(new EfId(id), firm(1));
  }

  static EfRef ef(String id, int firm) {
    return new EfRef(new EfId(id), firm(firm));
  }

  static FirmId firm(int n) {
    return new FirmId("firm-" + n);
  }

  static VersionId v(String s) {
    return new VersionId(s);
  }

  static Instant t(long seconds) {
    return T0.plusSeconds(seconds);
  }

  void advance(Duration d) {
    clock.advance(d);
  }

  /** Drains until nothing more is acked in one pass. */
  int drainAll() {
    int total = 0;
    int n;
    while ((n = relay.drainOnce()) > 0) {
      total += n;
    }
    return total;
  }
}
