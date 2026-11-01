package com.caseware.fanout.config;

import com.caseware.fanout.adapter.memory.FakeModelClient;
import com.caseware.fanout.adapter.memory.InMemoryBaselinesInUse;
import com.caseware.fanout.adapter.memory.InMemoryDiffSource;
import com.caseware.fanout.adapter.memory.InMemorySingleFlight;
import com.caseware.fanout.adapter.memory.InMemorySummaryJobQueue;
import com.caseware.fanout.adapter.memory.InMemorySummaryMetrics;
import com.caseware.fanout.adapter.memory.InMemorySummaryStore;
import com.caseware.fanout.port.FirmFlags;
import com.caseware.fanout.status.PendingUpdateQuery;
import com.caseware.fanout.status.VersionGraphProvider;
import com.caseware.fanout.summary.ModelClient;
import com.caseware.fanout.summary.ModelId;
import com.caseware.fanout.summary.PromptVersion;
import com.caseware.fanout.summary.SummaryMetrics;
import com.caseware.fanout.summary.MicrometerSummaryMetrics;
import com.caseware.fanout.summary.SummaryProperties;
import com.caseware.fanout.summary.SummaryPublishListener;
import com.caseware.fanout.summary.SummaryQuery;
import com.caseware.fanout.summary.SummaryService;
import com.caseware.fanout.summary.SummaryValidator;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Spec 05 beans, only when {@code fanout.summary.enabled=true} (default false) and the status
 * beans exist ({@code fanout.status.enabled} and {@code fanout.capture.enabled}). In-memory
 * adapters only. No scheduler thread is started: nothing calls {@code processNext()} in this
 * module. A real {@link ModelClient} bean must be supplied; the deterministic stub is only
 * created with {@code fanout.summary.stub-model=true}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    name = {"fanout.summary.enabled", "fanout.status.enabled", "fanout.capture.enabled"},
    havingValue = "true")
public class SummaryConfiguration {

  /** Required: {@code fanout.summary.pinned-model} and {@code fanout.summary.prompt-version}. */
  @Bean
  SummaryProperties summaryProperties(Environment env) {
    String model = env.getProperty("fanout.summary.pinned-model");
    String prompt = env.getProperty("fanout.summary.prompt-version");
    if (model == null || model.isBlank()) {
      throw new IllegalStateException("fanout.summary.pinned-model is required (a pinned version, not an alias)");
    }
    if (prompt == null || prompt.isBlank()) {
      throw new IllegalStateException("fanout.summary.prompt-version is required");
    }
    SummaryProperties d = new SummaryProperties(new ModelId(model), new PromptVersion(prompt));
    return new SummaryProperties(
        d.pinnedModel(),
        d.prompt(),
        env.getProperty("fanout.summary.max-attempts", Integer.class, d.maxAttempts()),
        env.getProperty("fanout.summary.lease-ttl", Duration.class, d.leaseTtl()),
        env.getProperty("fanout.summary.base-backoff", Duration.class, d.baseBackoff()),
        env.getProperty("fanout.summary.max-backoff", Duration.class, d.maxBackoff()),
        env.getProperty("fanout.summary.unsupported-max", Double.class, d.unsupportedMax()),
        env.getProperty("fanout.summary.omission-max", Double.class, d.omissionMax()));
  }

  @Bean
  @ConditionalOnProperty(prefix = "fanout.summary", name = "stub-model", havingValue = "true")
  FakeModelClient summaryStubModel() {
    return new FakeModelClient();
  }

  @Bean
  InMemorySummaryStore summaryStore() {
    return new InMemorySummaryStore();
  }

  @Bean
  InMemorySummaryJobQueue summaryJobQueue(Clock clock) {
    return new InMemorySummaryJobQueue(clock);
  }

  @Bean
  InMemorySingleFlight summarySingleFlight(Clock clock) {
    return new InMemorySingleFlight(clock);
  }

  @Bean
  InMemoryDiffSource summaryDiffSource() {
    return new InMemoryDiffSource();
  }

  @Bean
  InMemoryBaselinesInUse summaryBaselinesInUse() {
    return new InMemoryBaselinesInUse();
  }

  @Bean
  SummaryMetrics summaryMetrics(ObjectProvider<MeterRegistry> registry) {
    MeterRegistry r = registry.getIfAvailable();
    return r != null ? new MicrometerSummaryMetrics(r) : new InMemorySummaryMetrics();
  }

  @Bean
  SummaryValidator summaryValidator() {
    return new SummaryValidator();
  }

  @Bean
  SummaryService summaryService(
      InMemorySummaryStore store,
      InMemoryDiffSource diffs,
      ModelClient model,
      SummaryValidator validator,
      InMemorySingleFlight singleFlight,
      InMemorySummaryJobQueue queue,
      InMemoryBaselinesInUse baselines,
      VersionGraphProvider graphs,
      SummaryProperties props,
      SummaryMetrics metrics,
      Clock clock) {
    return new SummaryService(
        store, diffs, model, validator, singleFlight, queue, baselines, graphs, props, metrics, clock);
  }

  @Bean
  SummaryQuery summaryQuery(PendingUpdateQuery status, FirmFlags flags, SummaryService service) {
    return new SummaryQuery(status, flags, service);
  }

  @Bean
  SummaryPublishListener summaryPublishListener(SummaryService service) {
    return new SummaryPublishListener(service);
  }
}
