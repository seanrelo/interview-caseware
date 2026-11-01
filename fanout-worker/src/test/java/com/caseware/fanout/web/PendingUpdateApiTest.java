package com.caseware.fanout.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.fanout.domain.EfId;
import com.caseware.fanout.domain.Page;
import com.caseware.fanout.status.Caller;
import com.caseware.fanout.status.PendingUpdateQuery;
import com.caseware.fanout.status.StatusCounts;
import com.caseware.fanout.status.StatusPage;
import com.caseware.fanout.status.StatusRow;
import com.caseware.fanout.status.StatusUnavailableException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class PendingUpdateApiTest {

  private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, String firm) {
    return b.header("X-Dev-Firm", firm).header("X-Dev-User", "demo-user");
  }

  private static MockHttpServletRequestBuilder aliceGet(String url) {
    return as(get(url), "firm-a");
  }

  private static MockHttpServletRequestBuilder aliceDecision(String url, String body) {
    return as(post(url), "firm-a").contentType(MediaType.APPLICATION_JSON).content(body);
  }

  /** Runs the test body with a standalone MockMvc over the real in-memory services. */
  private static void withRig(java.util.function.Consumer<Rig> body) {
    WebRig.runner()
        .run(
            ctx -> {
              WebRig.seed(ctx);
              body.accept(new Rig(ctx.getBean(PendingUpdateController.class),
                  ctx.getBean(SummaryController.class), ctx.getBean(PendingUpdateQuery.class)));
            });
  }

  private record Rig(PendingUpdateController pending, SummaryController summary, PendingUpdateQuery query) {
    MockMvc mvc() {
      return WebRig.mvc(pending, summary);
    }
  }

  @Test
  @DisplayName("FR-22, NFR-14: list returns the caller's rows and totals, newest status derived from the graph")
  void listRowsAndTotals() {
    withRig(r -> {
      try {
        r.mvc().perform(aliceGet("/api/v1/pending-updates"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.rows.length()").value(3))
            .andExpect(jsonPath("$.rows[0].ef").value("ef1"))
            .andExpect(jsonPath("$.rows[0].status").value("UPDATE_PENDING"))
            .andExpect(jsonPath("$.rows[0].baseline").value("v1"))
            .andExpect(jsonPath("$.rows[0].pendingTarget").value("v3"))
            .andExpect(jsonPath("$.rows[0].template").value("TPL-A"))
            .andExpect(jsonPath("$.rows[0].source").value("DERIVED"))
            .andExpect(jsonPath("$.rows[0].updatedAt").isString())
            .andExpect(jsonPath("$.rows[2].status").value("UP_TO_DATE"))
            .andExpect(jsonPath("$.nextToken").value((Object) null))
            .andExpect(jsonPath("$.totals.upToDate").value(1))
            .andExpect(jsonPath("$.totals.updatePending").value(2))
            .andExpect(jsonPath("$.totals.unknown").value(0));
      } catch (Exception e) {
        throw new AssertionError(e);
      }
    });
  }

  @Test
  @DisplayName("FR-22: size paginates with a nextToken that returns the next page")
  void pagination() {
    withRig(r -> {
      try {
        String body = r.mvc().perform(aliceGet("/api/v1/pending-updates?size=2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.rows.length()").value(2))
            .andExpect(jsonPath("$.nextToken").value("ef2"))
            .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("ef1");
        r.mvc().perform(aliceGet("/api/v1/pending-updates?size=2&token=ef2"))
            .andExpect(jsonPath("$.rows.length()").value(1))
            .andExpect(jsonPath("$.rows[0].ef").value("ef3"))
            .andExpect(jsonPath("$.nextToken").value((Object) null));
      } catch (Exception e) {
        throw new AssertionError(e);
      }
    });
  }

  @Test
  @DisplayName("NFR-14: size is clamped to 1..200 and a non-numeric size is a 400")
  void sizeClamp() {
    AtomicInteger seen = new AtomicInteger();
    PendingUpdateQuery q = new PendingUpdateQuery() {
      public Optional<StatusPage> list(Caller c, Optional<String> t, int size) {
        seen.set(size);
        return Optional.of(new StatusPage(new Page<>(List.of(), Optional.empty()), new StatusCounts(0, 0, 0)));
      }
      public Optional<StatusRow> detail(Caller c, EfId ef) {
        return Optional.empty();
      }
    };
    MockMvc mvc = WebRig.mvc(new PendingUpdateController(new CallerResolver(), q, null));
    try {
      mvc.perform(aliceGet("/api/v1/pending-updates?size=0")).andExpect(status().isOk());
      assertThat(seen).hasValue(1);
      mvc.perform(aliceGet("/api/v1/pending-updates?size=-5")).andExpect(status().isOk());
      assertThat(seen).hasValue(1);
      mvc.perform(aliceGet("/api/v1/pending-updates?size=5000")).andExpect(status().isOk());
      assertThat(seen).hasValue(200);
      mvc.perform(aliceGet("/api/v1/pending-updates")).andExpect(status().isOk());
      assertThat(seen).hasValue(50);
      mvc.perform(aliceGet("/api/v1/pending-updates?size=abc"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  @Test
  @DisplayName("NFR-41: list with the firm flag off (empty query result) is 404 FEATURE_OFF")
  void listFeatureOff() throws Exception {
    PendingUpdateQuery off = new StubQuery(Optional.empty(), Optional.empty(), null);
    WebRig.mvc(new PendingUpdateController(new CallerResolver(), off, null))
        .perform(aliceGet("/api/v1/pending-updates"))
        .andExpect(status().isNotFound())
        .andExpect(content().json("{\"error\":\"FEATURE_OFF\"}"));
  }

  @Test
  @DisplayName("FR-25: a status read failure is 503 STATUS_UNAVAILABLE for list and detail, never up to date")
  void statusUnavailable() throws Exception {
    PendingUpdateQuery broken = new StubQuery(null, null, new StatusUnavailableException("down", null));
    MockMvc mvc = WebRig.mvc(new PendingUpdateController(new CallerResolver(), broken, null));
    for (String url : List.of("/api/v1/pending-updates", "/api/v1/pending-updates/ef1")) {
      String body = mvc.perform(aliceGet(url))
          .andExpect(status().isServiceUnavailable())
          .andExpect(jsonPath("$.error").value("STATUS_UNAVAILABLE"))
          .andExpect(jsonPath("$.message").value("unknown, not up to date"))
          .andReturn().getResponse().getContentAsString();
      assertThat(body).doesNotContain("UP_TO_DATE").doesNotContain("rows");
    }
  }

  @Test
  @DisplayName("FR-22: detail returns one row")
  void detail() {
    withRig(r -> {
      try {
        r.mvc().perform(aliceGet("/api/v1/pending-updates/ef3"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ef").value("ef3"))
            .andExpect(jsonPath("$.status").value("UP_TO_DATE"))
            .andExpect(jsonPath("$.pendingTarget").value((Object) null));
      } catch (Exception e) {
        throw new AssertionError(e);
      }
    });
  }

  @Test
  @DisplayName("NFR-33: a foreign EF is 404 NOT_FOUND with a body identical to an unknown EF")
  void foreignEqualsUnknown() {
    withRig(r -> {
      try {
        String foreign = r.mvc().perform(aliceGet("/api/v1/pending-updates/ef9"))
            .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        String unknown = r.mvc().perform(aliceGet("/api/v1/pending-updates/nope"))
            .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        assertThat(foreign).isEqualTo(unknown).isEqualTo("{\"error\":\"NOT_FOUND\"}");
        String fs = r.mvc().perform(aliceGet("/api/v1/pending-updates/ef9/summary"))
            .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        String us = r.mvc().perform(aliceGet("/api/v1/pending-updates/nope/summary"))
            .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        assertThat(fs).isEqualTo(us);
        String fd = r.mvc().perform(aliceDecision("/api/v1/pending-updates/ef9/accept", "{\"version\":\"v3\"}"))
            .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        String ud = r.mvc().perform(aliceDecision("/api/v1/pending-updates/nope/accept", "{\"version\":\"v3\"}"))
            .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        assertThat(fd).isEqualTo(ud).isEqualTo(foreign);
      } catch (Exception e) {
        throw new AssertionError(e);
      }
    });
  }

  @Test
  @DisplayName("NFR-33: missing or blank dev headers are 401 UNAUTHENTICATED on every endpoint")
  void unauthenticated() {
    withRig(r -> {
      try {
        MockMvc mvc = r.mvc();
        for (MockHttpServletRequestBuilder b : List.of(
            get("/api/v1/pending-updates"),
            get("/api/v1/pending-updates/ef1"),
            get("/api/v1/pending-updates/ef1/summary"),
            post("/api/v1/pending-updates/ef1/accept").contentType(MediaType.APPLICATION_JSON).content("{\"version\":\"v3\"}"),
            post("/api/v1/pending-updates/ef1/decline").contentType(MediaType.APPLICATION_JSON).content("{\"version\":\"v3\"}"),
            get("/api/v1/pending-updates").header("X-Dev-Firm", "firm-a"),
            get("/api/v1/pending-updates").header("X-Dev-User", "u"),
            get("/api/v1/pending-updates").header("X-Dev-Firm", " ").header("X-Dev-User", "u"),
            get("/api/v1/pending-updates").header("X-Dev-Firm", "firm-a").header("X-Dev-User", ""))) {
          mvc.perform(b).andExpect(status().isUnauthorized()).andExpect(content().json("{\"error\":\"UNAUTHENTICATED\"}"));
        }
      } catch (Exception e) {
        throw new AssertionError(e);
      }
    });
  }

  @Test
  @DisplayName("NFR-33: the firm in query or body is ignored; only the header decides")
  void firmInQueryOrBodyIgnored() {
    withRig(r -> {
      try {
        MockMvc mvc = r.mvc();
        mvc.perform(aliceGet("/api/v1/pending-updates?firm=firm-b&firmId=firm-b"))
            .andExpect(jsonPath("$.rows.length()").value(3))
            .andExpect(jsonPath("$.rows[?(@.ef=='ef9')]").isEmpty());
        mvc.perform(aliceDecision("/api/v1/pending-updates/ef9/accept", "{\"version\":\"v3\",\"firm\":\"firm-b\",\"firmId\":\"firm-b\"}"))
            .andExpect(status().isNotFound());
        // Caller from firm B asking for ef9 with a firm A header value in the query still sees B's own EF.
        mvc.perform(as(get("/api/v1/pending-updates/ef9?firm=firm-a"), "firm-b"))
            .andExpect(status().isOk());
      } catch (Exception e) {
        throw new AssertionError(e);
      }
    });
  }

  @Test
  @DisplayName("FR-27, FR-32: accept round trip records and refreshes the row; a repeat once the row is no longer pending is 422 STALE_OR_NOT_PENDING (DUPLICATE is covered in DecisionRulesRegressionTest)")
  void acceptRoundTrip() {
    withRig(r -> {
      try {
        MockMvc mvc = r.mvc();
        mvc.perform(aliceDecision("/api/v1/pending-updates/ef1/accept", "{\"version\":\"v3\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.result").value("RECORDED"))
            .andExpect(jsonPath("$.row.ef").value("ef1"));
        mvc.perform(aliceDecision("/api/v1/pending-updates/ef1/accept", "{\"version\":\"v3\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().json("{\"error\":\"STALE_OR_NOT_PENDING\"}"));
      } catch (Exception e) {
        throw new AssertionError(e);
      }
    });
  }

  @Test
  @DisplayName("FR-31, FR-29: decline pins (EF, version): the EF is no longer pending for that version")
  void declineRoundTrip() {
    withRig(r -> {
      try {
        MockMvc mvc = r.mvc();
        mvc.perform(aliceDecision("/api/v1/pending-updates/ef2/decline", "{\"version\":\"v3\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.result").value("RECORDED"))
            .andExpect(jsonPath("$.row.status").value("UP_TO_DATE"));
        mvc.perform(aliceGet("/api/v1/pending-updates/ef2"))
            .andExpect(jsonPath("$.status").value("UP_TO_DATE"));
        // the other EF is not affected
        mvc.perform(aliceGet("/api/v1/pending-updates/ef1"))
            .andExpect(jsonPath("$.status").value("UPDATE_PENDING"));
      } catch (Exception e) {
        throw new AssertionError(e);
      }
    });
  }

  @Test
  @DisplayName("FR-23, FR-25: a version the graph does not know is 422 UNKNOWN_VERSION and nothing is recorded")
  void unknownVersion() {
    withRig(r -> {
      try {
        MockMvc mvc = r.mvc();
        mvc.perform(aliceDecision("/api/v1/pending-updates/ef1/decline", "{\"version\":\"v99\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().json("{\"error\":\"UNKNOWN_VERSION\"}"));
        mvc.perform(aliceGet("/api/v1/pending-updates/ef1")).andExpect(jsonPath("$.status").value("UPDATE_PENDING"));
      } catch (Exception e) {
        throw new AssertionError(e);
      }
    });
  }

  @Test
  @DisplayName("NFR-41: a decision while the firm flag is off is 404 FEATURE_OFF")
  void decisionFeatureOff() {
    WebRig.runner().run(ctx -> {
      WebRig.seed(ctx);
      ((com.caseware.fanout.adapter.memory.InMemoryFirmFlags) ctx.getBean(com.caseware.fanout.port.FirmFlags.class))
          .setEnabled(WebRig.F, false);
      MockMvc mvc = WebRig.mvc(ctx.getBean(PendingUpdateController.class));
      try {
        mvc.perform(aliceDecision("/api/v1/pending-updates/ef1/accept", "{\"version\":\"v3\"}"))
            .andExpect(status().isNotFound())
            .andExpect(content().json("{\"error\":\"FEATURE_OFF\"}"));
        mvc.perform(aliceGet("/api/v1/pending-updates"))
            .andExpect(status().isNotFound())
            .andExpect(content().json("{\"error\":\"FEATURE_OFF\"}"));
      } catch (Exception e) {
        throw new AssertionError(e);
      }
    });
  }

  @Test
  @DisplayName("FR-27: a missing or blank version or a malformed body is 400; the template is never read from the client")
  void badDecisionBody() {
    withRig(r -> {
      try {
        MockMvc mvc = r.mvc();
        for (String body : List.of("{}", "{\"version\":\" \"}", "not json", "")) {
          mvc.perform(aliceDecision("/api/v1/pending-updates/ef1/accept", body))
              .andExpect(status().isBadRequest())
              .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
        }
        // a client-sent template is ignored (unknown property would only matter if it were used)
        mvc.perform(aliceDecision("/api/v1/pending-updates/ef1/decline", "{\"version\":\"v3\",\"template\":\"OTHER\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.row.template").value("TPL-A"));
      } catch (Exception e) {
        throw new AssertionError(e);
      }
    });
  }

  @Test
  @DisplayName("NFR-30: any other decision store failure is 503 DECISION_UNAVAILABLE")
  void decisionStoreFailure() {
    WebRig.runner().run(ctx -> {
      WebRig.seed(ctx);
      var failing = new com.caseware.fanout.status.DecisionService(
          new com.caseware.fanout.status.DecisionStore() {
            public com.caseware.fanout.status.DecisionResult append(com.caseware.fanout.status.Decision d) {
              throw new IllegalStateException("store down");
            }
            public java.util.Set<com.caseware.fanout.domain.VersionId> declined(EfId e, com.caseware.fanout.domain.TemplateId t) {
              return java.util.Set.of();
            }
            public java.util.Set<com.caseware.fanout.domain.VersionId> accepted(EfId e, com.caseware.fanout.domain.TemplateId t) {
              return java.util.Set.of();
            }
          },
          ctx.getBean(com.caseware.fanout.status.StatusService.class),
          ctx.getBean(com.caseware.fanout.capture.CaptureHook.class),
          ctx.getBean(com.caseware.fanout.port.FirmFlags.class),
          ctx.getBean(java.time.Clock.class));
      var api = new DecisionApi(failing, ctx.getBean(PendingUpdateQuery.class),
          ctx.getBean(com.caseware.fanout.port.FirmFlags.class), com.caseware.fanout.domain.Region.EU,
          ctx.getBean(com.caseware.fanout.status.StatusService.class));
      MockMvc mvc = WebRig.mvc(new PendingUpdateController(new CallerResolver(), ctx.getBean(PendingUpdateQuery.class), api));
      try {
        mvc.perform(aliceDecision("/api/v1/pending-updates/ef1/accept", "{\"version\":\"v3\"}"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(content().json("{\"error\":\"DECISION_UNAVAILABLE\"}"));
      } catch (Exception e) {
        throw new AssertionError(e);
      }
    });
  }

  @Test
  @DisplayName("FR-32: when the post-decision refresh fails the decision still answers 200 with row null")
  void rowNullWhenRefreshFails() {
    WebRig.runner().run(ctx -> {
      WebRig.seed(ctx);
      PendingUpdateQuery real = ctx.getBean(PendingUpdateQuery.class);
      AtomicInteger calls = new AtomicInteger();
      PendingUpdateQuery flaky = new PendingUpdateQuery() {
        public Optional<StatusPage> list(Caller c, Optional<String> t, int s) {
          return real.list(c, t, s);
        }
        public Optional<StatusRow> detail(Caller c, EfId ef) {
          if (calls.incrementAndGet() > 1) {
            throw new StatusUnavailableException("down", null);
          }
          return real.detail(c, ef);
        }
      };
      var api = new DecisionApi(ctx.getBean(com.caseware.fanout.status.DecisionService.class), flaky,
          ctx.getBean(com.caseware.fanout.port.FirmFlags.class), com.caseware.fanout.domain.Region.EU,
          ctx.getBean(com.caseware.fanout.status.StatusService.class));
      try {
        WebRig.mvc(new PendingUpdateController(new CallerResolver(), flaky, api))
            .perform(aliceDecision("/api/v1/pending-updates/ef1/decline", "{\"version\":\"v3\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.result").value("RECORDED"))
            .andExpect(jsonPath("$.row").value((Object) null));
      } catch (Exception e) {
        throw new AssertionError(e);
      }
    });
  }

  private record StubQuery(Optional<StatusPage> page, Optional<StatusRow> row, RuntimeException fail)
      implements PendingUpdateQuery {
    public Optional<StatusPage> list(Caller c, Optional<String> t, int s) {
      if (fail != null) throw fail;
      return page;
    }
    public Optional<StatusRow> detail(Caller c, EfId ef) {
      if (fail != null) throw fail;
      return row;
    }
  }
}
