package com.caseware.fanout.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.caseware.fanout.adapter.memory.FakeModelClient;
import com.caseware.fanout.domain.EfId;
import com.caseware.fanout.port.FirmFlags;
import com.caseware.fanout.status.Caller;
import com.caseware.fanout.status.PendingUpdateQuery;
import com.caseware.fanout.summary.SummaryQuery;
import com.caseware.fanout.summary.SummaryService;
import com.caseware.fanout.summary.SummaryView;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class SummaryApiTest {

  private static MockHttpServletRequestBuilder req(String url) {
    return get(url).header("X-Dev-Firm", "firm-a").header("X-Dev-User", "demo-user");
  }

  @Test
  @DisplayName("FR-34, FR-42: GENERATING first, READY after the job runs, with audit fields and no path (FR-38, FR-40, FR-43)")
  void generatingThenReady() {
    WebRig.runner().run(ctx -> {
      WebRig.seed(ctx);
      MockMvc mvc = WebRig.mvc(ctx.getBean(SummaryController.class));
      mvc.perform(req("/api/v1/pending-updates/ef1/summary"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.state").value("GENERATING"));
      assertThat(ctx.getBean(SummaryService.class).processNext()).isEqualTo(1);
      String body = mvc.perform(req("/api/v1/pending-updates/ef2/summary"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.state").value("READY"))
          .andExpect(jsonPath("$.label").value("AI-generated"))
          .andExpect(jsonPath("$.generationId").isString())
          .andExpect(jsonPath("$.generatedAt").isString())
          .andExpect(jsonPath("$.model").value("sample-model-2026-01-15"))
          .andExpect(jsonPath("$.promptVersion").value("summary-prompt-v3"))
          .andExpect(jsonPath("$.baseline").value("v1"))
          .andExpect(jsonPath("$.target").value("v3"))
          .andExpect(jsonPath("$.statements.length()").value(2))
          .andExpect(jsonPath("$.statements[0].diffItemIds[0]").value("d1"))
          .andExpect(jsonPath("$.diff.length()").value(2))
          .andExpect(jsonPath("$.diff[0].id").value("d1"))
          .andExpect(jsonPath("$.diff[0].section").value("Revenue"))
          .andExpect(jsonPath("$.diff[0].change").value("title renamed"))
          .andReturn().getResponse().getContentAsString();
      assertThat(body).doesNotContain("path").doesNotContain("$.sections").doesNotContain("secret");
    });
  }

  @Test
  @DisplayName("FR-42, NFR-30: a pair whose generation dead-lettered is 200 UNAVAILABLE and the status row is unchanged")
  void failedIsUnavailableAndStatusUnchanged() {
    WebRig.runner("fanout.summary.max-attempts=1").run(ctx -> {
      WebRig.seed(ctx);
      ctx.getBean(FakeModelClient.class).failNext(10);
      MockMvc mvc = WebRig.mvc(ctx.getBean(PendingUpdateController.class), ctx.getBean(SummaryController.class));
      String before = mvc.perform(req("/api/v1/pending-updates/ef1")).andReturn().getResponse().getContentAsString();
      mvc.perform(req("/api/v1/pending-updates/ef1/summary")).andExpect(jsonPath("$.state").value("GENERATING"));
      ctx.getBean(SummaryService.class).processNext();
      mvc.perform(req("/api/v1/pending-updates/ef1/summary"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.state").value("UNAVAILABLE"))
          .andExpect(jsonPath("$.message").value("summary unavailable"));
      String after = mvc.perform(req("/api/v1/pending-updates/ef1")).andReturn().getResponse().getContentAsString();
      assertThat(after).isEqualTo(before);
    });
  }

  @Test
  @DisplayName("FR-42, NFR-30: an exception from the summary side is 200 UNAVAILABLE, never 5xx, and detail is unchanged")
  void exceptionIsUnavailable() {
    WebRig.runner().run(ctx -> {
      WebRig.seed(ctx);
      SummaryQuery boom = new SummaryQuery(ctx.getBean(PendingUpdateQuery.class), ctx.getBean(FirmFlags.class),
          ctx.getBean(SummaryService.class)) {
        @Override
        public Optional<SummaryView> summaryFor(Caller caller, EfId ef) {
          throw new IllegalStateException("model exploded");
        }
      };
      MockMvc mvc = WebRig.mvc(new SummaryController(new CallerResolver(), boom),
          ctx.getBean(PendingUpdateController.class));
      String before = mvc.perform(req("/api/v1/pending-updates/ef1")).andReturn().getResponse().getContentAsString();
      mvc.perform(req("/api/v1/pending-updates/ef1/summary"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.state").value("UNAVAILABLE"));
      assertThat(mvc.perform(req("/api/v1/pending-updates/ef1")).andReturn().getResponse().getContentAsString())
          .isEqualTo(before);
    });
  }

  @Test
  @DisplayName("FR-34: an EF with no pending update has no summary: 404 NOT_FOUND")
  void upToDateHasNoSummary() {
    WebRig.runner().run(ctx -> {
      WebRig.seed(ctx);
      WebRig.mvc(ctx.getBean(SummaryController.class))
          .perform(req("/api/v1/pending-updates/ef3/summary"))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    });
  }
}
