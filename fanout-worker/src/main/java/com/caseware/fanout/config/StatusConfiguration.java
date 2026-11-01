package com.caseware.fanout.config;

import com.caseware.fanout.adapter.memory.InMemoryDecisionStore;
import com.caseware.fanout.adapter.memory.InMemoryEfFirmLookup;
import com.caseware.fanout.adapter.memory.InMemoryStatusStore;
import com.caseware.fanout.adapter.memory.InMemoryTemplateVersionRecordStore;
import com.caseware.fanout.adapter.memory.InMemoryVersionRegistry;
import com.caseware.fanout.capture.CaptureHook;
import com.caseware.fanout.port.FirmFlags;
import com.caseware.fanout.status.DecisionService;
import com.caseware.fanout.status.DecisionStore;
import com.caseware.fanout.status.EfFirmLookup;
import com.caseware.fanout.status.PendingUpdateQuery;
import com.caseware.fanout.status.PendingUpdateQueryService;
import com.caseware.fanout.status.StatusService;
import com.caseware.fanout.status.StatusStore;
import com.caseware.fanout.status.VersionGraphProvider;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spec 04 beans, only when {@code fanout.status.enabled=true} (default false). Additive: it needs
 * {@code fanout.capture.enabled=true} too (record store and capture hook) and is registered next
 * to {@link FanoutConfiguration}, which it does not change. No thread is started.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "fanout.status", name = "enabled", havingValue = "true")
public class StatusConfiguration {

  @Bean
  InMemoryVersionRegistry statusVersionRegistry() {
    return new InMemoryVersionRegistry();
  }

  @Bean
  VersionGraphProvider statusGraphProvider(InMemoryVersionRegistry registry) {
    return registry::snapshot;
  }

  @Bean
  InMemoryEfFirmLookup efFirmLookup() {
    return new InMemoryEfFirmLookup();
  }

  @Bean
  InMemoryStatusStore statusStore() {
    return new InMemoryStatusStore();
  }

  @Bean
  InMemoryDecisionStore decisionStore() {
    return new InMemoryDecisionStore();
  }

  @Bean
  StatusService statusService(
      InMemoryTemplateVersionRecordStore records,
      VersionGraphProvider graphs,
      DecisionStore decisions,
      StatusStore store,
      FirmFlags flags,
      EfFirmLookup firms,
      Clock clock) {
    return new StatusService(records, records, graphs, decisions, store, flags, firms, clock);
  }

  @Bean
  DecisionService decisionService(
      DecisionStore decisions,
      StatusService status,
      CaptureHook hook,
      FirmFlags flags,
      Clock clock) {
    return new DecisionService(decisions, status, hook, flags, clock);
  }

  @Bean
  PendingUpdateQuery pendingUpdateQuery(
      StatusStore store, FirmFlags flags, EfFirmLookup firms, MeterRegistry registry) {
    return new PendingUpdateQueryService(store, flags, firms, registry);
  }
}
