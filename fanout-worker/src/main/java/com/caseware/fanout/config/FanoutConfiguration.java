package com.caseware.fanout.config;

import com.caseware.fanout.adapter.memory.InMemoryCaptureDeadLetterSink;
import com.caseware.fanout.adapter.memory.InMemoryCaptureOutbox;
import com.caseware.fanout.adapter.memory.InMemoryDeadLetterSink;
import com.caseware.fanout.adapter.memory.InMemoryFirmFlags;
import com.caseware.fanout.adapter.memory.InMemoryPendingUpdateMarker;
import com.caseware.fanout.adapter.memory.InMemoryPublishProgressStore;
import com.caseware.fanout.adapter.memory.InMemoryTemplateVersionIndex;
import com.caseware.fanout.adapter.memory.InMemoryTemplateVersionRecordStore;
import com.caseware.fanout.adapter.memory.InMemoryVersionGraphReader;
import com.caseware.fanout.adapter.memory.InMemoryWorkItemStore;
import com.caseware.fanout.adapter.memory.RecordBackedBaselineLookup;
import com.caseware.fanout.adapter.memory.RecordBackedTemplateVersionIndex;
import com.caseware.fanout.app.CompletionTracker;
import com.caseware.fanout.app.DeadLetterMetrics;
import com.caseware.fanout.app.Dispatcher;
import com.caseware.fanout.app.ItemProcessor;
import com.caseware.fanout.app.PublishHandler;
import com.caseware.fanout.app.RetryingFailureHandler;
import com.caseware.fanout.app.VersionSupersessionCheck;
import com.caseware.fanout.adapter.memory.InMemoryEfCatalog;
import com.caseware.fanout.adapter.memory.InMemoryEfLoader;
import com.caseware.fanout.capture.AdjustableConcurrencyLimit;
import com.caseware.fanout.capture.BackfillJob;
import com.caseware.fanout.capture.BackfillMetrics;
import com.caseware.fanout.capture.CaptureHook;
import com.caseware.fanout.capture.ConcurrencyLimit;
import com.caseware.fanout.capture.EfCatalog;
import com.caseware.fanout.capture.EfLoader;
import com.caseware.fanout.capture.CaptureHooks;
import com.caseware.fanout.capture.CaptureMetrics;
import com.caseware.fanout.capture.CaptureOutbox;
import com.caseware.fanout.capture.CaptureRelay;
import com.caseware.fanout.capture.TemplateVersionRecordStore;
import com.caseware.fanout.graph.BaselineLookup;
import com.caseware.fanout.limiter.AdaptiveLimiter;
import com.caseware.fanout.limiter.FirmFairness;
import com.caseware.fanout.limiter.LimiterMetrics;
import com.caseware.fanout.port.DeadLetterSink;
import com.caseware.fanout.port.FirmFlags;
import com.caseware.fanout.port.PendingUpdateMarker;
import com.caseware.fanout.port.PublishProgressStore;
import com.caseware.fanout.port.TemplateVersionIndex;
import com.caseware.fanout.port.VersionGraphReader;
import com.caseware.fanout.port.WorkItemStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.util.random.RandomGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * In-memory graph for one regional cell (NFR-35). No AWS adapter exists; replacing an adapter bean
 * replaces the port implementation. Nothing here starts a thread: the caller (scheduler, test)
 * invokes {@link Dispatcher#runOnce()}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FanoutProperties.class)
public class FanoutConfiguration {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  MeterRegistry meterRegistry() {
    return new SimpleMeterRegistry();
  }

  @Bean
  RandomGenerator retryRandom() {
    return RandomGenerator.getDefault();
  }

  @Bean
  WorkItemStore workItemStore() {
    return new InMemoryWorkItemStore();
  }

  @Bean
  TemplateVersionIndex templateVersionIndex() {
    return new InMemoryTemplateVersionIndex();
  }

  @Bean
  InMemoryVersionGraphReader versionGraphReader() {
    return new InMemoryVersionGraphReader();
  }

  @Bean
  InMemoryPendingUpdateMarker pendingUpdateMarker(VersionGraphReader graph) {
    return new InMemoryPendingUpdateMarker(graph);
  }

  @Bean
  FirmFlags firmFlags() {
    return new InMemoryFirmFlags();
  }

  @Bean
  PublishProgressStore publishProgressStore() {
    return new InMemoryPublishProgressStore();
  }

  @Bean
  DeadLetterSink deadLetterSink(Clock clock, FanoutProperties props) {
    return new InMemoryDeadLetterSink(clock, props.dlq().retention());
  }

  @Bean
  PublishedAtRegistry publishedAtRegistry() {
    return new PublishedAtRegistry();
  }

  @Bean
  FirmFairness firmFairness(FanoutProperties props, WorkItemStore store, Clock clock) {
    return new FirmFairness(
        props.limiter().firmShare(), () -> store.firmsWithQueuedWork(clock.instant()));
  }

  @Bean
  AdaptiveLimiter adaptiveLimiter(FanoutProperties props, Clock clock, FirmFairness fairness) {
    return new AdaptiveLimiter(props.limiter().toProperties(), clock, fairness);
  }

  @Bean
  CompletionTracker completionTracker(
      PublishProgressStore progress,
      WorkItemStore items,
      PublishedAtRegistry registry,
      Clock clock,
      MeterRegistry meters) {
    return new CompletionTracker(progress, items, registry::lookup, clock, meters);
  }

  @Bean
  RetryingFailureHandler failureHandler(
      WorkItemStore store,
      PendingUpdateMarker marker,
      DeadLetterSink deadLetters,
      FanoutProperties props,
      Clock clock,
      RandomGenerator random,
      CompletionTracker tracker) {
    return new RetryingFailureHandler(
        store, marker, deadLetters, props.retry().toPolicy(), clock, random, tracker);
  }

  @Bean
  ItemProcessor itemProcessor(
      WorkItemStore store,
      PendingUpdateMarker marker,
      FirmFlags flags,
      Clock clock,
      VersionGraphReader graph,
      RetryingFailureHandler failureHandler,
      AdaptiveLimiter limiter,
      CompletionTracker tracker) {
    return new ItemProcessor(
        store,
        marker,
        flags,
        clock,
        new VersionSupersessionCheck(graph),
        failureHandler,
        limiter,
        tracker);
  }

  @Bean
  Dispatcher dispatcher(
      WorkItemStore store,
      ItemProcessor processor,
      Clock clock,
      FanoutProperties props,
      AdaptiveLimiter limiter) {
    return new Dispatcher(
        store, processor, clock, props.leaseDuration(), props.batchSize(), limiter::saturatedFirms);
  }

  @Bean
  PublishHandler publishHandler(
      FanoutProperties props,
      TemplateVersionIndex index,
      WorkItemStore store,
      PublishProgressStore progress,
      Clock clock) {
    return new PublishHandler(props.region(), index, store, progress, clock, props.pageSize());
  }

  /**
   * Meters. The backlog gauge is the number of firms with ready work, not an item count: the store
   * port has no count operation and adding one is a port change left to the architect.
   */
  @Bean
  MeterBinder meterBinder(
      MeterRegistry registry,
      AdaptiveLimiter limiter,
      DeadLetterSink deadLetters,
      WorkItemStore store,
      Clock clock) {
    LimiterMetrics.register(
        registry, limiter, () -> store.firmsWithQueuedWork(clock.instant()).size());
    DeadLetterMetrics.bind(registry, deadLetters);
    return new MeterBinder();
  }

  /** Marker type so the metric registration runs as a bean. */
  static final class MeterBinder {}

  /**
   * Spec 01 capture beans, only when {@code fanout.capture.enabled=true} (default false). The
   * record-backed index is not an autowire candidate so the stub stays the one injected into
   * PublishHandler; switching means replacing the stub bean.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty(prefix = "fanout.capture", name = "enabled", havingValue = "true")
  @EnableConfigurationProperties(CaptureProperties.class)
  static class CaptureWiring {

    @Bean
    CaptureMetrics captureMetrics(MeterRegistry registry) {
      CaptureMetrics m = new CaptureMetrics();
      m.bind(registry);
      return m;
    }

    @Bean
    InMemoryTemplateVersionRecordStore templateVersionRecordStore(CaptureMetrics metrics) {
      return new InMemoryTemplateVersionRecordStore(metrics);
    }

    @Bean
    InMemoryCaptureDeadLetterSink captureDeadLetterSink(Clock clock) {
      return new InMemoryCaptureDeadLetterSink(clock);
    }

    @Bean
    InMemoryCaptureOutbox captureOutbox(
        Clock clock,
        CaptureProperties props,
        InMemoryCaptureDeadLetterSink deadLetters,
        CaptureMetrics metrics) {
      return new InMemoryCaptureOutbox(
          clock, props.maxAttempts(), props.outboxCapacity(), deadLetters, metrics);
    }

    @Bean
    CaptureRelay captureRelay(
        TemplateVersionRecordStore store,
        CaptureOutbox outbox,
        Clock clock,
        RandomGenerator retryRandom,
        CaptureMetrics metrics,
        CaptureProperties props) {
      return new CaptureRelay(store, outbox, clock, retryRandom, metrics, props.relay().toConfig());
    }

    @Bean
    CaptureHook captureHook(
        FirmFlags flags, CaptureOutbox outbox, CaptureMetrics metrics, CaptureProperties props) {
      return new CaptureHooks(flags, outbox, metrics, props.fallbackCapacity());
    }

    @Bean(autowireCandidate = false)
    RecordBackedTemplateVersionIndex recordBackedTemplateVersionIndex(
        InMemoryTemplateVersionRecordStore store) {
      return new RecordBackedTemplateVersionIndex(store);
    }

    @Bean
    InMemoryEfCatalog efCatalog() {
      return new InMemoryEfCatalog();
    }

    @Bean
    InMemoryEfLoader efLoader() {
      return new InMemoryEfLoader();
    }

    @Bean
    ConcurrencyLimit backfillConcurrencyLimit(CaptureProperties props) {
      return new AdjustableConcurrencyLimit(props.backfill().concurrency());
    }

    @Bean
    BackfillMetrics backfillMetrics(MeterRegistry registry) {
      BackfillMetrics m = new BackfillMetrics();
      m.bind(registry);
      return m;
    }

    /** The job bean only: no scheduler thread; the caller invokes step or run. */
    @Bean
    BackfillJob backfillJob(
        EfCatalog catalog,
        EfLoader loader,
        TemplateVersionRecordStore store,
        FirmFlags flags,
        @org.springframework.beans.factory.annotation.Qualifier("backfillConcurrencyLimit")
            ConcurrencyLimit limit,
        Clock clock,
        RandomGenerator retryRandom,
        BackfillMetrics metrics,
        CaptureProperties props) {
      return new BackfillJob(
          catalog, loader, store, flags, limit, clock, retryRandom, metrics,
          props.backfill().toConfig());
    }

    @Bean
    BaselineLookup recordBackedBaselineLookup(TemplateVersionRecordStore store) {
      return new RecordBackedBaselineLookup(store);
    }
  }
}
