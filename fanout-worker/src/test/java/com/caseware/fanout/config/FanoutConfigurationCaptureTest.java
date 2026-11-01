package com.caseware.fanout.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.caseware.fanout.adapter.memory.RecordBackedBaselineLookup;
import com.caseware.fanout.adapter.memory.RecordBackedTemplateVersionIndex;
import com.caseware.fanout.app.PublishHandler;
import com.caseware.fanout.capture.CaptureHook;
import com.caseware.fanout.capture.CaptureMetrics;
import com.caseware.fanout.capture.CaptureOutbox;
import com.caseware.fanout.capture.CaptureRelay;
import com.caseware.fanout.capture.TemplateVersionRecordStore;
import com.caseware.fanout.domain.EfId;
import com.caseware.fanout.domain.EfRef;
import com.caseware.fanout.domain.FirmId;
import com.caseware.fanout.domain.Region;
import com.caseware.fanout.domain.TemplateId;
import com.caseware.fanout.domain.VersionId;
import com.caseware.fanout.graph.BaselineLookup;
import com.caseware.fanout.port.TemplateVersionIndex;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

class FanoutConfigurationCaptureTest {

  private static AnnotationConfigApplicationContext context(Map<String, Object> props) {
    var ctx = new AnnotationConfigApplicationContext();
    ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", props));
    ctx.register(FanoutConfiguration.class);
    ctx.refresh();
    return ctx;
  }

  @Test
  @DisplayName("NFR-40 NFR-42: flag off (default) creates no capture beans")
  void offByDefault() {
    try (var ctx = context(Map.of())) {
      assertThat(ctx.getBeansOfType(CaptureHook.class)).isEmpty();
      assertThat(ctx.getBeansOfType(CaptureOutbox.class)).isEmpty();
      assertThat(ctx.getBeansOfType(CaptureRelay.class)).isEmpty();
      assertThat(ctx.getBeansOfType(BaselineLookup.class)).isEmpty();
      assertThat(ctx.getBean(PublishHandler.class)).isNotNull();
    }
  }

  @Test
  @DisplayName("NFR-40: fanout.capture.enabled=true wires store, outbox, relay, hooks and the record-backed adapters")
  void onWiresCaptureEndToEnd() {
    try (var ctx = context(Map.of("fanout.capture.enabled", "true", "fanout.capture.max-attempts", "7"))) {
      assertThat(ctx.getBean(TemplateVersionRecordStore.class)).isNotNull();
      assertThat(ctx.getBean(CaptureOutbox.class)).isNotNull();
      assertThat(ctx.getBean(RecordBackedBaselineLookup.class)).isSameAs(ctx.getBean(BaselineLookup.class));
      // the stub stays the injected TemplateVersionIndex
      assertThat(ctx.getBean(TemplateVersionIndex.class)).isNotInstanceOf(RecordBackedTemplateVersionIndex.class);
      assertThat(ctx.getBean("recordBackedTemplateVersionIndex")).isInstanceOf(RecordBackedTemplateVersionIndex.class);
      assertThat(ctx.getBean(PublishHandler.class)).isNotNull();

      var ef = new EfRef(new EfId("ef-1"), new FirmId("f1"));
      var tpl = new TemplateId("tpl");
      ctx.getBean(CaptureHook.class).onCreate(Region.EU, ef, tpl, new VersionId("v1"), Instant.parse("2026-10-30T10:00:00Z"));
      assertThat(ctx.getBean(CaptureRelay.class).drainOnce()).isEqualTo(1);
      assertThat(ctx.getBean(BaselineLookup.class).baselineOf(Region.EU, ef, tpl)).contains(new VersionId("v1"));
      var page = ((RecordBackedTemplateVersionIndex) ctx.getBean("recordBackedTemplateVersionIndex"))
          .findEfsByTemplate(Region.EU, tpl, Optional.empty(), 10);
      assertThat(page.items()).containsExactly(ef);
      assertThat(ctx.getBean(MeterRegistry.class).find("capture.upsert").tag("result", "APPLIED").functionCounter().count())
          .isEqualTo(1.0);
      assertThat(ctx.getBean(CaptureMetrics.class).get(com.caseware.fanout.capture.UpsertResult.APPLIED)).isEqualTo(1);
    }
  }

  @Test
  @DisplayName("NFR-40: invalid capture limits fail fast at startup")
  void invalidLimitsRejected() {
    assertThatThrownBy(() -> context(Map.of("fanout.capture.enabled", "true", "fanout.capture.max-attempts", "0")))
        .hasRootCauseInstanceOf(IllegalArgumentException.class);
  }
}
