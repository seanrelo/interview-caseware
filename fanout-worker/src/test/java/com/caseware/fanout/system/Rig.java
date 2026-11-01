package com.caseware.fanout.system;

import com.caseware.fanout.adapter.memory.InMemoryDeadLetterSink;
import com.caseware.fanout.adapter.memory.InMemoryFirmFlags;
import com.caseware.fanout.adapter.memory.InMemoryPendingUpdateMarker;
import com.caseware.fanout.adapter.memory.InMemoryPublishProgressStore;
import com.caseware.fanout.adapter.memory.InMemoryTemplateVersionIndex;
import com.caseware.fanout.adapter.memory.InMemoryVersionGraphReader;
import com.caseware.fanout.adapter.memory.InMemoryWorkItemStore;
import com.caseware.fanout.app.CallGate;
import com.caseware.fanout.app.CompletionTracker;
import com.caseware.fanout.app.Dispatcher;
import com.caseware.fanout.app.ItemProcessor;
import com.caseware.fanout.app.MutableClock;
import com.caseware.fanout.app.PublishHandler;
import com.caseware.fanout.app.RetryingFailureHandler;
import com.caseware.fanout.app.VersionSupersessionCheck;
import com.caseware.fanout.domain.EfId;
import com.caseware.fanout.domain.EfRef;
import com.caseware.fanout.domain.FirmId;
import com.caseware.fanout.domain.ItemState;
import com.caseware.fanout.domain.PublishEvent;
import com.caseware.fanout.domain.Region;
import com.caseware.fanout.domain.TemplateId;
import com.caseware.fanout.domain.VersionId;
import com.caseware.fanout.domain.WorkKey;
import com.caseware.fanout.fault.FlakyMarker;
import com.caseware.fanout.limiter.AdaptiveLimiter;
import com.caseware.fanout.limiter.FirmFairness;
import com.caseware.fanout.limiter.LimiterProperties;
import com.caseware.fanout.limiter.RetryPolicy;
import com.caseware.fanout.port.PendingUpdateMarker;
import com.caseware.fanout.port.WorkItemStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/**
 * Full wiring used by the system tests: PublishHandler, Dispatcher, ItemProcessor,
 * VersionSupersessionCheck, RetryingFailureHandler, optional AdaptiveLimiter, CompletionTracker,
 * over the in-memory ports and the fault decorators. Fake clock only; nothing sleeps.
 */
final class Rig {

  static final Instant T0 = Instant.parse("2026-10-30T10:00:00Z");
  static final TemplateId TPL = new TemplateId("tpl");
  static final Region REGION = Region.EU;
  static final Duration LEASE = Duration.ofMinutes(5);
  /** Larger than the retry cap (30 s) so one step makes every backed-off item ready. */
  static final Duration STEP = Duration.ofSeconds(31);

  final MutableClock clock = new MutableClock(T0);
  final InMemoryWorkItemStore durable = new InMemoryWorkItemStore();
  final WorkItemStore store;
  final InMemoryVersionGraphReader graph = new InMemoryVersionGraphReader();
  final InMemoryPendingUpdateMarker real = new InMemoryPendingUpdateMarker(graph);
  final FlakyMarker marker;
  final PendingUpdateMarker downstream;
  final InMemoryTemplateVersionIndex index = new InMemoryTemplateVersionIndex();
  final InMemoryPublishProgressStore progress = new InMemoryPublishProgressStore();
  final InMemoryDeadLetterSink dlq = new InMemoryDeadLetterSink(clock, Duration.ofDays(14));
  final InMemoryFirmFlags flags = new InMemoryFirmFlags();
  final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  final Map<String, Instant> publishedAt = new ConcurrentHashMap<>();
  final CompletionTracker tracker;
  final AdaptiveLimiter limiter; // null when unlimited
  final ItemProcessor processor;
  final Dispatcher dispatcher;
  final PublishHandler handler;
  final List<WorkKey> keys = new ArrayList<>();

  Rig(int maxInFlight, int batch, UnaryOperator<WorkItemStore> storeWrap, int pageSize) {
    this(maxInFlight, batch, storeWrap, pageSize, UnaryOperator.identity());
  }

  Rig(
      int maxInFlight,
      int batch,
      UnaryOperator<WorkItemStore> storeWrap,
      int pageSize,
      UnaryOperator<PendingUpdateMarker> markerWrap) {
    this.store = storeWrap.apply(durable);
    this.marker = new FlakyMarker(real, 7L);
    this.downstream = markerWrap.apply(marker);
    this.tracker =
        new CompletionTracker(
            progress, durable, id -> Optional.ofNullable(publishedAt.get(id)), clock, registry);
    RetryingFailureHandler failures =
        new RetryingFailureHandler(
            store,
            downstream,
            dlq,
            new RetryPolicy(5, Duration.ofSeconds(1), Duration.ofSeconds(30)),
            clock,
            new SplittableRandom(11),
            tracker);
    CallGate gate;
    if (maxInFlight > 0) {
      LimiterProperties p = LimiterProperties.defaults(maxInFlight);
      limiter =
          new AdaptiveLimiter(
              p,
              clock,
              new FirmFairness(p.firmShare(), () -> durable.firmsWithQueuedWork(clock.instant())));
      gate = limiter;
    } else {
      limiter = null;
      gate = CallGate.UNLIMITED;
    }
    this.processor =
        new ItemProcessor(
            store,
            downstream,
            flags,
            clock,
            new VersionSupersessionCheck(graph),
            failures,
            gate,
            tracker);
    this.dispatcher =
        limiter == null
            ? new Dispatcher(store, processor, clock, LEASE, batch)
            : new Dispatcher(store, processor, clock, LEASE, batch, limiter::saturatedFirms);
    this.handler = new PublishHandler(REGION, index, store, progress, clock, pageSize);
  }

  Rig() {
    this(0, 50, UnaryOperator.identity(), 25);
  }

  // ---- fixtures

  static VersionId v(String s) {
    return new VersionId(s);
  }

  static EfId ef(int i) {
    return new EfId(String.format("ef-%05d", i));
  }

  static FirmId firm(int i) {
    return new FirmId("firm-" + i);
  }

  /** Registers n EFs spread over {@code firms} firms. */
  Rig efs(int n, int firms) {
    for (int i = 0; i < n; i++) {
      index.register(REGION, TPL, new EfRef(ef(i), firm(i % firms)));
    }
    return this;
  }

  Rig version(String id, long secondsBeforeT0) {
    graph.addVersion(TPL, v(id), T0.minusSeconds(secondsBeforeT0), true);
    return this;
  }

  WorkKey key(int i, String version) {
    return new WorkKey(ef(i), TPL, v(version));
  }

  PublishEvent event(String publishId, String version) {
    publishedAt.putIfAbsent(publishId, clock.instant());
    return new PublishEvent(publishId, TPL, v(version), publishedAt.get(publishId));
  }

  // ---- driving

  /** Drains, then moves the clock one step, until no item is ready. Returns rounds used. */
  int settle() {
    for (int round = 1; round <= 200; round++) {
      dispatcher.drain();
      clock.advance(STEP);
      if (durable.firmsWithQueuedWork(clock.instant()).isEmpty()) {
        return round;
      }
    }
    throw new AssertionError("did not settle in 200 rounds");
  }

  ItemState state(WorkKey k) {
    return durable.find(k).orElseThrow().state();
  }

  long count(String publishId, ItemState s) {
    long n = 0;
    for (WorkKey k : keys) {
      var it = durable.find(k);
      if (it.isPresent() && it.get().publishId().equals(publishId) && it.get().state() == s) {
        n++;
      }
    }
    return n;
  }
}
