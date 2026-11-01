package com.caseware.fanout.web;

import com.caseware.fanout.domain.Region;
import com.caseware.fanout.port.FirmFlags;
import com.caseware.fanout.status.DecisionService;
import com.caseware.fanout.status.PendingUpdateQuery;
import com.caseware.fanout.status.StatusService;
import com.caseware.fanout.summary.SummaryQuery;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * HTTP layer, only when {@code fanout.web.enabled=true} (default false) and the status beans
 * exist ({@code fanout.status.enabled} and {@code fanout.capture.enabled}). The summary endpoint
 * additionally needs {@code fanout.summary.enabled}. Region of this cell: {@code fanout.web.region}
 * (default EU). The caller resolver is DEV ONLY, NOT AUTH, see {@link CallerResolver}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    name = {"fanout.web.enabled", "fanout.status.enabled", "fanout.capture.enabled"},
    havingValue = "true")
public class WebConfiguration {

  @Bean
  CallerResolver callerResolver() {
    return new CallerResolver();
  }

  @Bean
  ApiExceptionHandler apiExceptionHandler() {
    return new ApiExceptionHandler();
  }

  @Bean
  DecisionApi decisionApi(
      DecisionService decisions, PendingUpdateQuery query, FirmFlags flags,
      StatusService status, Environment env) {
    Region region = Region.valueOf(env.getProperty("fanout.web.region", "EU").strip().toUpperCase());
    return new DecisionApi(decisions, query, flags, region, status);
  }

  @Bean
  PendingUpdateController pendingUpdateController(
      CallerResolver callers, PendingUpdateQuery query, DecisionApi decisions) {
    return new PendingUpdateController(callers, query, decisions);
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty(prefix = "fanout.summary", name = "enabled", havingValue = "true")
  static class SummaryWebConfiguration {
    @Bean
    SummaryController summaryController(CallerResolver callers, SummaryQuery summaries) {
      return new SummaryController(callers, summaries);
    }
  }
}
