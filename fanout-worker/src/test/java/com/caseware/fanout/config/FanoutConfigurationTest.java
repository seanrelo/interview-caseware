package com.caseware.fanout.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.fanout.app.Dispatcher;
import com.caseware.fanout.app.ItemProcessor;
import com.caseware.fanout.app.PublishHandler;
import com.caseware.fanout.domain.Region;
import com.caseware.fanout.limiter.AdaptiveLimiter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class FanoutConfigurationTest {

  @Test
  void defaultsBindToTheAgreedPlaceholders() {
    FanoutProperties p = bind(Map.of());
    assertThat(p.region()).isEqualTo(Region.EU);
    assertThat(p.retry().maxAttempts()).isEqualTo(5);
    assertThat(p.limiter().firmShare()).isEqualTo(0.25);
    assertThat(p.limiter().errorRateThreshold()).isEqualTo(0.10);
    assertThat(p.limiter().errorWindow()).isEqualTo(Duration.ofMinutes(1));
    assertThat(p.dlq().retention()).isEqualTo(Duration.ofDays(14));
  }

  @Test
  void overridesBind() {
    FanoutProperties p =
        bind(Map.of("fanout.region", "CA", "fanout.limiter.max-in-flight", "7", "fanout.retry.max-attempts", "3"));
    assertThat(p.region()).isEqualTo(Region.CA);
    assertThat(p.limiter().toProperties().maxInFlight()).isEqualTo(7);
    assertThat(p.retry().toPolicy().maxAttempts()).isEqualTo(3);
  }

  @Test
  void contextBootsWithAllBeansAndMeters() {
    try (var ctx = new AnnotationConfigApplicationContext(FanoutConfiguration.class)) {
      assertThat(ctx.getBean(Dispatcher.class)).isNotNull();
      assertThat(ctx.getBean(ItemProcessor.class)).isNotNull();
      assertThat(ctx.getBean(PublishHandler.class)).isNotNull();
      assertThat(ctx.getBean(AdaptiveLimiter.class).limit()).isEqualTo(50);
      MeterRegistry reg = ctx.getBean(MeterRegistry.class);
      assertThat(reg.find("fanout.backlog").gauge()).isNotNull();
      assertThat(reg.find("fanout.inflight").gauge()).isNotNull();
      assertThat(reg.find("fanout.limit").gauge()).isNotNull();
      assertThat(reg.find("dlq.depth").gauge()).isNotNull();
      assertThat(reg.find("publish.marker.latency").timer()).isNotNull();
      assertThat(ctx.getBean(Dispatcher.class).drain()).isZero();
    }
  }

  private static FanoutProperties bind(Map<String, String> props) {
    return new Binder(new MapConfigurationPropertySource(props))
        .bindOrCreate("fanout", FanoutProperties.class);
  }
}
