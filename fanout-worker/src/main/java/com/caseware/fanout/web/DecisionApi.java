package com.caseware.fanout.web;

import com.caseware.fanout.domain.EfId;
import com.caseware.fanout.domain.EfRef;
import com.caseware.fanout.domain.Region;
import com.caseware.fanout.port.FirmFlags;
import com.caseware.fanout.status.Caller;
import com.caseware.fanout.status.DecisionRejectedException;
import com.caseware.fanout.status.DecisionResult;
import com.caseware.fanout.status.DecisionService;
import com.caseware.fanout.status.DecisionType;
import com.caseware.fanout.status.PendingUpdateQuery;
import com.caseware.fanout.status.StatusRow;
import com.caseware.fanout.status.StatusService;
import com.caseware.fanout.status.UpdateStatus;
import com.caseware.fanout.status.StatusUnavailableException;
import com.caseware.fanout.domain.VersionId;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;
import java.util.Optional;

/**
 * Glue between an accept or decline request and {@link DecisionService}. The template is taken
 * from the EF's current status row, never from the client, and the EF reference is built from the
 * authenticated caller's firm (NFR-33). Failures become {@link DecisionRejectedException} or
 * {@link DecisionUnavailableException}.
 *
 * <p>Check order: flag off, foreign or unknown EF, malformed body, version unknown to the graph,
 * then the pending rule: a decision is allowed only on an UPDATE_PENDING row and only for that
 * row's pending target (FR-27, FR-31, FR-25). Anything else, including an UNKNOWN row, is
 * {@link StaleOrNotPendingException} and nothing is recorded.
 */
public class DecisionApi {

  private final DecisionService decisions;
  private final PendingUpdateQuery query;
  private final FirmFlags flags;
  private final Region region;
  private final StatusService status;
  private final ObjectMapper json = new ObjectMapper();

  public DecisionApi(
      DecisionService decisions, PendingUpdateQuery query, FirmFlags flags, Region region, StatusService status) {
    this.decisions = Objects.requireNonNull(decisions);
    this.query = Objects.requireNonNull(query);
    this.flags = Objects.requireNonNull(flags);
    this.region = Objects.requireNonNull(region);
    this.status = Objects.requireNonNull(status);
  }

  /** {@code rawBody} is the unparsed request body; it is bound here, after the caller is known. */
  public DecisionResponse decide(Caller caller, EfId ef, DecisionType type, String rawBody) {
    if (!flags.enabled(caller.firm())) {
      throw new DecisionRejectedException(DecisionRejectedException.Reason.FEATURE_OFF);
    }
    StatusRow current;
    try {
      current =
          query
              .detail(caller, ef)
              .orElseThrow(
                  () -> new DecisionRejectedException(DecisionRejectedException.Reason.NOT_YOUR_FIRM));
    } catch (StatusUnavailableException e) {
      throw new DecisionUnavailableException(e);
    }
    VersionId target = new VersionId(parseVersion(rawBody));
    // No template known (UNKNOWN row without a record): nothing a version could be pinned to.
    if (current.template().isEmpty()) {
      throw new DecisionRejectedException(DecisionRejectedException.Reason.UNKNOWN_VERSION);
    }
    try {
      if (!status.knowsVersion(current.template().get(), target)) {
        throw new DecisionRejectedException(DecisionRejectedException.Reason.UNKNOWN_VERSION);
      }
    } catch (DecisionRejectedException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new DecisionUnavailableException(e);
    }
    if (current.status() != UpdateStatus.UPDATE_PENDING
        || !current.pendingTarget().map(target::equals).orElse(false)) {
      throw new StaleOrNotPendingException();
    }
    EfRef ref = new EfRef(ef, caller.firm());
    DecisionResult result;
    try {
      result =
          type == DecisionType.ACCEPT
              ? decisions.accept(region, caller, ref, current.template().get(), target, null)
              : decisions.decline(region, caller, ref, current.template().get(), target, null);
    } catch (DecisionRejectedException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new DecisionUnavailableException(e);
    }
    return new DecisionResponse(result.name(), refreshed(caller, ef));
  }

  private String parseVersion(String rawBody) {
    if (rawBody == null || rawBody.isBlank()) {
      throw new BadRequestException("body is required");
    }
    JsonNode node;
    try {
      node = json.readTree(rawBody);
    } catch (Exception e) {
      throw new BadRequestException("malformed body");
    }
    JsonNode v = node == null ? null : node.get("version");
    if (v == null || !v.isTextual() || v.asText().isBlank()) {
      throw new BadRequestException("version is required");
    }
    return v.asText().strip();
  }

  private RowDto refreshed(Caller caller, EfId ef) {
    try {
      Optional<StatusRow> row = query.detail(caller, ef);
      return row.map(RowDto::from).orElse(null);
    } catch (RuntimeException e) {
      return null;
    }
  }
}
