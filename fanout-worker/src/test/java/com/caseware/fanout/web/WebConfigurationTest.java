package com.caseware.fanout.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.fanout.config.FanoutConfiguration;
import com.caseware.fanout.config.StatusConfiguration;
import com.caseware.fanout.config.SummaryConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class WebConfigurationTest {

  private static ApplicationContextRunner base(String... props) {
    return new ApplicationContextRunner()
        .withUserConfiguration(
            FanoutConfiguration.class, StatusConfiguration.class, SummaryConfiguration.class,
            WebConfiguration.class)
        .withPropertyValues(props);
  }

  private static final String[] STATUS = {"fanout.capture.enabled=true", "fanout.status.enabled=true"};

  @Test
  @DisplayName("NFR-41: with fanout.web.enabled unset no controller, resolver or advice bean exists")
  void offByDefault() {
    base(STATUS).run(ctx -> {
      assertThat(ctx.getBeansOfType(PendingUpdateController.class)).isEmpty();
      assertThat(ctx.getBeansOfType(SummaryController.class)).isEmpty();
      assertThat(ctx.getBeansOfType(CallerResolver.class)).isEmpty();
      assertThat(ctx.getBeansOfType(ApiExceptionHandler.class)).isEmpty();
    });
  }

  @Test
  @DisplayName("NFR-41: web enabled without the status beans creates nothing")
  void needsStatusBeans() {
    base("fanout.web.enabled=true").run(ctx -> assertThat(ctx.getBeansOfType(PendingUpdateController.class)).isEmpty());
    base("fanout.web.enabled=true", "fanout.capture.enabled=true")
        .run(ctx -> assertThat(ctx.getBeansOfType(PendingUpdateController.class)).isEmpty());
  }

  @Test
  @DisplayName("NFR-41: web plus status beans gives the pending-update controller but no summary controller")
  void summaryControllerNeedsSummaryFlag() {
    base("fanout.capture.enabled=true", "fanout.status.enabled=true", "fanout.web.enabled=true").run(ctx -> {
      assertThat(ctx).hasNotFailed();
      assertThat(ctx.getBeansOfType(PendingUpdateController.class)).hasSize(1);
      assertThat(ctx.getBeansOfType(SummaryController.class)).isEmpty();
    });
  }

  @Test
  @DisplayName("NFR-35: the region comes from fanout.web.region and defaults to EU; an invalid value fails startup")
  void region() {
    base(STATUS).withPropertyValues("fanout.web.enabled=true", "fanout.web.region=CA")
        .run(ctx -> assertThat(ctx).hasNotFailed());
    base(STATUS).withPropertyValues("fanout.web.enabled=true", "fanout.web.region=MARS")
        .run(ctx -> assertThat(ctx).hasFailed());
  }
}
