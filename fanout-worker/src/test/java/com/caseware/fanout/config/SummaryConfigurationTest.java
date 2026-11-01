package com.caseware.fanout.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.caseware.fanout.adapter.memory.InMemoryDiffSource;
import com.caseware.fanout.adapter.memory.InMemoryEfFirmLookup;
import com.caseware.fanout.adapter.memory.InMemoryBaselinesInUse;
import com.caseware.fanout.adapter.memory.InMemoryTemplateVersionRecordStore;
import com.caseware.fanout.adapter.memory.InMemoryVersionRegistry;
import com.caseware.fanout.capture.CaptureSource;
import com.caseware.fanout.domain.EfId;
import com.caseware.fanout.domain.EfRef;
import com.caseware.fanout.domain.FirmId;
import com.caseware.fanout.domain.Region;
import com.caseware.fanout.domain.TemplateId;
import com.caseware.fanout.domain.VersionId;
import com.caseware.fanout.graph.GraphPublishEvent;
import com.caseware.fanout.graph.MarketBranch;
import com.caseware.fanout.graph.TemplateVersion;
import com.caseware.fanout.graph.VersionStatus;
import com.caseware.fanout.status.Caller;
import com.caseware.fanout.status.StatusService;
import com.caseware.fanout.summary.Diff;
import com.caseware.fanout.summary.DiffItem;
import com.caseware.fanout.summary.PairKey;
import com.caseware.fanout.summary.SummaryPublishListener;
import com.caseware.fanout.summary.SummaryQuery;
import com.caseware.fanout.summary.SummaryService;
import com.caseware.fanout.summary.SummaryView;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

class SummaryConfigurationTest {

  private static AnnotationConfigApplicationContext context(Map<String, Object> props) {
    var ctx = new AnnotationConfigApplicationContext();
    ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", props));
    ctx.register(FanoutConfiguration.class, StatusConfiguration.class, SummaryConfiguration.class);
    ctx.refresh();
    return ctx;
  }

  private static final Map<String, Object> ON = Map.of(
      "fanout.capture.enabled", "true", "fanout.status.enabled", "true", "fanout.summary.enabled", "true",
      "fanout.summary.pinned-model", "model-2026-05-01", "fanout.summary.prompt-version", "p3",
      "fanout.summary.stub-model", "true");

  @Test
  @DisplayName("NFR-41: fanout.summary.enabled defaults to false and creates no summary beans")
  void offByDefault() {
    try (var ctx = context(Map.of("fanout.capture.enabled", "true", "fanout.status.enabled", "true"))) {
      assertThat(ctx.getBeansOfType(SummaryService.class)).isEmpty();
      assertThat(ctx.getBeansOfType(SummaryQuery.class)).isEmpty();
      assertThat(ctx.getBeansOfType(SummaryPublishListener.class)).isEmpty();
    }
  }

  @Test
  @DisplayName("NFR-41: summary on without the status beans creates nothing")
  void needsStatus() {
    try (var ctx = context(Map.of("fanout.summary.enabled", "true"))) {
      assertThat(ctx.getBeansOfType(SummaryService.class)).isEmpty();
    }
  }

  @Test
  @DisplayName("NFR-17: an alias as pinned model fails startup")
  void aliasRejected() {
    var props = new java.util.HashMap<>(ON);
    props.put("fanout.summary.pinned-model", "latest");
    assertThatThrownBy(() -> context(props)).hasRootCauseMessage(
        "model id looks like an alias, a pinned version is required: latest");
  }

  @Test
  @DisplayName("NFR-17: a missing prompt version fails startup")
  void promptRequired() {
    var props = new java.util.HashMap<>(ON);
    props.remove("fanout.summary.prompt-version");
    assertThatThrownBy(() -> context(props)).hasStackTraceContaining("fanout.summary.prompt-version is required");
  }

  @Test
  @DisplayName("FR-37: without the stub property no ModelClient is created and startup fails")
  void noStubNoModel() {
    var props = new java.util.HashMap<>(ON);
    props.remove("fanout.summary.stub-model");
    assertThatThrownBy(() -> context(props)).hasStackTraceContaining("ModelClient");
  }

  @Test
  @DisplayName("FR-34, FR-35: with the flag on a publish flows to a Ready, AI-generated summary")
  void onWiresEndToEnd() {
    try (var ctx = context(ON)) {
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
      PairKey pair = new PairKey(t, new VersionId("v1"), new VersionId("v2"));
      ctx.getBean(InMemoryDiffSource.class)
          .register(new Diff(pair, List.of(new DiffItem("d1", "$.sections[0].title", "Revenue", "title renamed"))));
      ctx.getBean(InMemoryBaselinesInUse.class).register(t, new VersionId("v1"));

      ctx.getBean(SummaryPublishListener.class).onPublish(new GraphPublishEvent(t, new VersionId("v2")));
      var service = ctx.getBean(SummaryService.class);
      assertThat(service.processNext()).isEqualTo(1);

      var view = ctx.getBean(SummaryQuery.class).summaryFor(new Caller(firm, "alice"), ef.ef());
      assertThat(view).containsInstanceOf(SummaryView.Ready.class);
      assertThat(((SummaryView.Ready) view.orElseThrow()).label()).isEqualTo("AI-generated");
    }
  }
}
