package com.caseware.fanout.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.fanout.adapter.memory.InMemoryDecisionStore;
import com.caseware.fanout.adapter.memory.InMemoryEfFirmLookup;
import com.caseware.fanout.adapter.memory.InMemoryTemplateVersionRecordStore;
import com.caseware.fanout.adapter.memory.InMemoryVersionRegistry;
import com.caseware.fanout.capture.CaptureSource;
import com.caseware.fanout.domain.EfId;
import com.caseware.fanout.domain.EfRef;
import com.caseware.fanout.domain.FirmId;
import com.caseware.fanout.domain.Region;
import com.caseware.fanout.domain.TemplateId;
import com.caseware.fanout.domain.VersionId;
import com.caseware.fanout.graph.MarketBranch;
import com.caseware.fanout.graph.TemplateVersion;
import com.caseware.fanout.graph.VersionStatus;
import com.caseware.fanout.status.Caller;
import com.caseware.fanout.status.DecisionService;
import com.caseware.fanout.status.PendingUpdateQuery;
import com.caseware.fanout.status.StatusService;
import com.caseware.fanout.status.UpdateStatus;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

class StatusConfigurationTest {

  private static AnnotationConfigApplicationContext context(Map<String, Object> props) {
    var ctx = new AnnotationConfigApplicationContext();
    ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", props));
    ctx.register(FanoutConfiguration.class, StatusConfiguration.class);
    ctx.refresh();
    return ctx;
  }

  @Test
  @DisplayName("NFR-40: fanout.status.enabled defaults to false and creates no status beans")
  void offByDefault() {
    try (var ctx = context(Map.of("fanout.capture.enabled", "true"))) {
      assertThat(ctx.getBeansOfType(StatusService.class)).isEmpty();
      assertThat(ctx.getBeansOfType(PendingUpdateQuery.class)).isEmpty();
    }
  }

  @Test
  @DisplayName("NFR-40: with the flag on a decline is visible through the query")
  void onWiresEndToEnd() {
    try (var ctx = context(Map.of("fanout.capture.enabled", "true", "fanout.status.enabled", "true"))) {
      TemplateId t = new TemplateId("T");
      FirmId firm = new FirmId("F");
      EfRef ef = new EfRef(new EfId("e1"), firm);
      var reg = ctx.getBean(InMemoryVersionRegistry.class);
      reg.register(new TemplateVersion(t, new VersionId("v1"), Set.of(), new MarketBranch("main"),
          VersionStatus.PUBLISHED, Instant.parse("2026-01-01T00:00:00Z")));
      reg.register(new TemplateVersion(t, new VersionId("v2"), Set.of(new VersionId("v1")),
          new MarketBranch("main"), VersionStatus.PUBLISHED, Instant.parse("2026-01-02T00:00:00Z")));
      ctx.getBean(InMemoryEfFirmLookup.class).add(ef);
      ctx.getBean(InMemoryTemplateVersionRecordStore.class)
          .upsertVersion(Region.EU, ef, t, new VersionId("v1"), Instant.now(), CaptureSource.CREATE);
      ctx.getBean(StatusService.class).refresh(Region.EU, ef);

      Caller caller = new Caller(firm, "alice");
      var query = ctx.getBean(PendingUpdateQuery.class);
      assertThat(query.detail(caller, ef.ef())).map(r -> r.status()).contains(UpdateStatus.UPDATE_PENDING);
      ctx.getBean(DecisionService.class).decline(Region.EU, caller, ef, t, new VersionId("v2"), null);
      assertThat(query.detail(caller, ef.ef())).map(r -> r.status()).contains(UpdateStatus.UP_TO_DATE);
      assertThat(query.list(caller, Optional.empty(), 10).orElseThrow().totals().total()).isEqualTo(1);
      assertThat(ctx.getBean(InMemoryDecisionStore.class).declined(ef.ef(), t)).hasSize(1);
    }
  }
}
