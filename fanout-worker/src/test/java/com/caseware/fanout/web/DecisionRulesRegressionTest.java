package com.caseware.fanout.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.caseware.fanout.adapter.memory.InMemoryDecisionStore;
import com.caseware.fanout.adapter.memory.InMemoryEfFirmLookup;
import com.caseware.fanout.adapter.memory.InMemoryFirmFlags;
import com.caseware.fanout.capture.CaptureHook;
import com.caseware.fanout.domain.EfId;
import com.caseware.fanout.domain.EfRef;
import com.caseware.fanout.domain.Region;
import com.caseware.fanout.domain.TemplateId;
import com.caseware.fanout.domain.VersionId;
import com.caseware.fanout.port.FirmFlags;
import com.caseware.fanout.status.Caller;
import com.caseware.fanout.status.Decision;
import com.caseware.fanout.status.DecisionResult;
import com.caseware.fanout.status.DecisionService;
import com.caseware.fanout.status.DecisionStore;
import com.caseware.fanout.status.PendingUpdateQuery;
import com.caseware.fanout.status.StatusPage;
import com.caseware.fanout.status.StatusRow;
import com.caseware.fanout.status.StatusService;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Decision rules at the HTTP edge (FR-27, FR-31, FR-25, NFR-33): a decision is allowed only on an
 * UPDATE_PENDING row for exactly its pending target; otherwise 422 STALE_OR_NOT_PENDING and nothing
 * reaches the decision store. A malformed body never hides a missing identity.
 */
class DecisionRulesRegressionTest {

  private static final String BASE = "/api/v1/pending-updates/";

  /** Counts appends and delegates to the context's real in-memory store, which status derivation also reads. */
  private static final class CountingStore implements DecisionStore {
    final InMemoryDecisionStore real;
    final AtomicInteger appends = new AtomicInteger();

    CountingStore(InMemoryDecisionStore real) {
      this.real = real;
    }

    public DecisionResult append(Decision d) {
      appends.incrementAndGet();
      return real.append(d);
    }

    public Set<VersionId> declined(EfId e, TemplateId t) {
      return real.declined(e, t);
    }

    public Set<VersionId> accepted(EfId e, TemplateId t) {
      return real.accepted(e, t);
    }
  }

  private record Env(MockMvc mvc, CountingStore store) {}

  /** Real services, with the decision store replaced by a counting wrapper; optional query decorator. */
  private static void run(Function<PendingUpdateQuery, PendingUpdateQuery> decorate, java.util.function.Consumer<Env> body) {
    WebRig.runner().run(ctx -> {
      WebRig.seed(ctx);
      addUnknownRow(ctx);
      CountingStore store = new CountingStore(ctx.getBean(InMemoryDecisionStore.class));
      FirmFlags flags = ctx.getBean(FirmFlags.class);
      var service = new DecisionService(store, ctx.getBean(StatusService.class),
          ctx.getBean(CaptureHook.class), flags, ctx.getBean(Clock.class));
      PendingUpdateQuery query = decorate.apply(ctx.getBean(PendingUpdateQuery.class));
      var api = new DecisionApi(service, query, flags, Region.EU, ctx.getBean(StatusService.class));
      MockMvc mvc = WebRig.mvc(new PendingUpdateController(new CallerResolver(), query, api));
      body.accept(new Env(mvc, store));
    });
  }

  private static void run(java.util.function.Consumer<Env> body) {
    run(q -> q, body);
  }

  /** ef-unk belongs to firm A but has no template record, so its row is UNKNOWN. */
  private static void addUnknownRow(ApplicationContext ctx) {
    ctx.getBean(InMemoryEfFirmLookup.class).add(new EfRef(new EfId("ef-unk"), WebRig.F));
    ctx.getBean(StatusService.class).refresh(Region.EU, new EfRef(new EfId("ef-unk"), WebRig.F));
  }

  private static MockHttpServletRequestBuilder decision(String ef, String verb, String body) {
    return post(BASE + ef + "/" + verb).header("X-Dev-Firm", "firm-a").header("X-Dev-User", "u")
        .contentType(MediaType.APPLICATION_JSON).content(body);
  }

  private static String version(String v) {
    return "{\"version\":\"" + v + "\"}";
  }

  private static void expectStale(Env env, String ef, String verb, String v) throws Exception {
    env.mvc().perform(decision(ef, verb, version(v)))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(content().json("{\"error\":\"STALE_OR_NOT_PENDING\"}"));
  }

  @Test
  @DisplayName("FR-27/FR-31/QA-W3: accept or decline of a known version that is not the pending target is 422 and records nothing")
  void staleVersionRejected() throws Exception {
    run(env -> {
      try {
        // ef1 is on v1, pending target v3. v2 is known but is not the pending version.
        expectStale(env, "ef1", "accept", "v2");
        expectStale(env, "ef1", "decline", "v2");
        expectStale(env, "ef1", "accept", "v1");
        assertThat(env.store().appends).hasValue(0);
        env.mvc().perform(get(BASE + "ef1").header("X-Dev-Firm", "firm-a").header("X-Dev-User", "u"))
            .andExpect(jsonPath("$.status").value("UPDATE_PENDING"));
      } catch (Exception e) {
        throw new AssertionError(e);
      }
    });
  }

  @Test
  @DisplayName("FR-25/QA-W4: accept and decline on an UP_TO_DATE EF are 422 and record nothing")
  void decisionOnUpToDateRejected() {
    run(env -> {
      try {
        // ef3 is on v3 (latest) -> UP_TO_DATE; v1 is an older known version, v3 is its own baseline.
        expectStale(env, "ef3", "accept", "v1");
        expectStale(env, "ef3", "decline", "v1");
        expectStale(env, "ef3", "accept", "v3");
        expectStale(env, "ef3", "decline", "v3");
        assertThat(env.store().appends).hasValue(0);
      } catch (Exception e) {
        throw new AssertionError(e);
      }
    });
  }

  @Test
  @DisplayName("FR-25: an UNKNOWN row cannot be flipped by a user decision (accept and decline are 422, nothing recorded)")
  void unknownRowRejected() {
    run(env -> {
      try {
        env.mvc().perform(get(BASE + "ef-unk").header("X-Dev-Firm", "firm-a").header("X-Dev-User", "u"))
            .andExpect(jsonPath("$.status").value("UNKNOWN"));
        // No template is known for this row, so no version can be pinned: the existing UNKNOWN_VERSION rule applies.
        for (String verb : List.of("accept", "decline")) {
          env.mvc().perform(decision("ef-unk", verb, version("v3")))
              .andExpect(status().isUnprocessableEntity());
        }
        assertThat(env.store().appends).hasValue(0);
        env.mvc().perform(get(BASE + "ef-unk").header("X-Dev-Firm", "firm-a").header("X-Dev-User", "u"))
            .andExpect(jsonPath("$.status").value("UNKNOWN"));
      } catch (Exception e) {
        throw new AssertionError(e);
      }
    });
  }

  @Test
  @DisplayName("FR-25: UNKNOWN with a template and a graph-known version is 422 STALE_OR_NOT_PENDING, nothing recorded")
  void unknownStatusWithTemplate() {
    PendingUpdateQuery asUnknown = new PendingUpdateQuery() {
      public Optional<StatusPage> list(Caller c, Optional<String> t, int s) {
        return Optional.empty();
      }
      public Optional<StatusRow> detail(Caller c, EfId ef) {
        return Optional.of(new StatusRow(WebRig.F, ef, Optional.of(WebRig.T),
            com.caseware.fanout.status.UpdateStatus.UNKNOWN, Optional.empty(), Optional.empty(),
            java.time.Instant.parse("2026-01-10T00:00:00Z"), com.caseware.fanout.status.StatusSource.FANOUT_UNKNOWN));
      }
    };
    run(q -> asUnknown, env -> {
      try {
        expectStale(env, "ef1", "accept", "v3");
        expectStale(env, "ef1", "decline", "v3");
        assertThat(env.store().appends).hasValue(0);
      } catch (Exception e) {
        throw new AssertionError(e);
      }
    });
  }

  @Test
  @DisplayName("FR-23: a version unknown to the graph stays 422 UNKNOWN_VERSION (checked before the pending rule)")
  void unknownVersionComesFirst() {
    run(env -> {
      try {
        for (String ef : List.of("ef1", "ef3")) {
          env.mvc().perform(decision(ef, "accept", version("v99")))
              .andExpect(status().isUnprocessableEntity())
              .andExpect(content().json("{\"error\":\"UNKNOWN_VERSION\"}"));
        }
        assertThat(env.store().appends).hasValue(0);
      } catch (Exception e) {
        throw new AssertionError(e);
      }
    });
  }

  @Test
  @DisplayName("FR-27: the pending target is accepted and recorded once; a repeat after the row refreshed is 422")
  void repeatAfterRefreshIsStale() {
    run(env -> {
      try {
        env.mvc().perform(decision("ef1", "accept", version("v3")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.result").value("RECORDED"));
        // The row is refreshed and no longer pending, so the repeat is rejected (the UI re-reads).
        expectStale(env, "ef1", "accept", "v3");
        env.mvc().perform(decision("ef2", "decline", version("v3")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.result").value("RECORDED"));
        expectStale(env, "ef2", "decline", "v3");
        assertThat(env.store().appends).hasValue(2);
      } catch (Exception e) {
        throw new AssertionError(e);
      }
    });
  }

  @Test
  @DisplayName("FR-27: a duplicate for the same still-pending target is 200 DUPLICATE (row not yet refreshed)")
  void duplicateWhileStillPending() {
    // Query frozen on the pre-decision row, as when the status refresh has not landed yet.
    run(real -> {
      StatusRow[] frozen = new StatusRow[1];
      return new PendingUpdateQuery() {
        public Optional<StatusPage> list(Caller c, Optional<String> t, int s) {
          return real.list(c, t, s);
        }
        public Optional<StatusRow> detail(Caller c, EfId ef) {
          if (frozen[0] == null) {
            frozen[0] = real.detail(c, ef).orElseThrow();
          }
          return Optional.of(frozen[0]);
        }
      };
    }, env -> {
      try {
        env.mvc().perform(decision("ef1", "accept", version("v3")))
            .andExpect(jsonPath("$.result").value("RECORDED"));
        env.mvc().perform(decision("ef1", "accept", version("v3")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.result").value("DUPLICATE"));
        assertThat(env.store().real.accepted(new EfId("ef1"), WebRig.T)).containsExactly(new VersionId("v3"));
      } catch (Exception e) {
        throw new AssertionError(e);
      }
    });
  }

  @Test
  @DisplayName("NFR-33/QA-W1: malformed or missing body with no identity headers is 401, not 400")
  void unauthenticatedBeforeBadBody() {
    run(env -> {
      try {
        for (String verb : List.of("accept", "decline")) {
          for (String body : List.of("{nope", "", "{}", "not json", "[1]")) {
            env.mvc().perform(post(BASE + "ef1/" + verb).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"error\":\"UNAUTHENTICATED\"}"));
          }
          env.mvc().perform(post(BASE + "ef1/" + verb)).andExpect(status().isUnauthorized());
          // one header only, still unauthenticated
          env.mvc().perform(post(BASE + "ef1/" + verb).header("X-Dev-Firm", "firm-a").contentType(MediaType.APPLICATION_JSON).content("{nope"))
              .andExpect(status().isUnauthorized());
        }
        assertThat(env.store().appends).hasValue(0);
      } catch (Exception e) {
        throw new AssertionError(e);
      }
    });
  }

  @Test
  @DisplayName("QA-W1: with valid headers a malformed or missing body is still 400 BAD_REQUEST")
  void badBodyWithHeadersStill400() {
    run(env -> {
      try {
        for (String body : List.of("{nope", "", "{}", "not json", "[1]", "{\"version\":5}", "{\"version\":\" \"}")) {
          env.mvc().perform(decision("ef1", "accept", body))
              .andExpect(status().isBadRequest())
              .andExpect(content().json("{\"error\":\"BAD_REQUEST\"}"));
        }
        assertThat(env.store().appends).hasValue(0);
      } catch (Exception e) {
        throw new AssertionError(e);
      }
    });
  }

  @Test
  @DisplayName("Check order: flag off and foreign or unknown EF (404) come before a bad body (400)")
  void flagAndOwnershipBeforeBody() {
    WebRig.runner().run(ctx -> {
      WebRig.seed(ctx);
      MockMvc mvc = WebRig.mvc(ctx.getBean(PendingUpdateController.class));
      try {
        for (String ef : List.of("ef9", "nope")) {
          mvc.perform(decision(ef, "accept", "{nope"))
              .andExpect(status().isNotFound())
              .andExpect(content().json("{\"error\":\"NOT_FOUND\"}"));
        }
        ((InMemoryFirmFlags) ctx.getBean(FirmFlags.class)).setEnabled(WebRig.F, false);
        mvc.perform(decision("ef1", "accept", "{nope"))
            .andExpect(status().isNotFound())
            .andExpect(content().json("{\"error\":\"FEATURE_OFF\"}"));
      } catch (Exception e) {
        throw new AssertionError(e);
      }
    });
  }
}
