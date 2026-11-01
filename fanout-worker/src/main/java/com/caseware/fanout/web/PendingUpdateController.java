package com.caseware.fanout.web;

import com.caseware.fanout.domain.EfId;
import com.caseware.fanout.status.Caller;
import com.caseware.fanout.status.DecisionType;
import com.caseware.fanout.status.PendingUpdateQuery;
import com.caseware.fanout.status.StatusPage;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Thin adapter over {@link PendingUpdateQuery} and {@link DecisionApi}. No logic beyond mapping. */
@RestController
@RequestMapping("/api/v1/pending-updates")
public class PendingUpdateController {

  static final int DEFAULT_SIZE = 50;
  static final int MAX_SIZE = 200;

  private final CallerResolver callers;
  private final PendingUpdateQuery query;
  private final DecisionApi decisions;

  public PendingUpdateController(
      CallerResolver callers, PendingUpdateQuery query, DecisionApi decisions) {
    this.callers = callers;
    this.query = query;
    this.decisions = decisions;
  }

  @GetMapping
  public ResponseEntity<?> list(
      HttpServletRequest request,
      @RequestParam(name = "size", required = false) String size,
      @RequestParam(name = "token", required = false) String token) {
    Caller caller = callers.resolve(request);
    int n = clamp(size);
    Optional<String> t = token == null || token.isBlank() ? Optional.empty() : Optional.of(token);
    Optional<StatusPage> page = query.list(caller, t, n);
    if (page.isEmpty()) {
      return ResponseEntity.status(404).body(ErrorBody.of("FEATURE_OFF"));
    }
    StatusPage p = page.get();
    return ResponseEntity.ok(
        new PageDto(
            p.rows().items().stream().map(RowDto::from).toList(),
            p.rows().nextToken().orElse(null),
            new PageDto.Totals(
                p.totals().upToDate(), p.totals().updatePending(), p.totals().unknown())));
  }

  @GetMapping("/{efId}")
  public ResponseEntity<?> detail(HttpServletRequest request, @PathVariable String efId) {
    Caller caller = callers.resolve(request);
    return query
        .detail(caller, new EfId(efId))
        .<ResponseEntity<?>>map(r -> ResponseEntity.ok(RowDto.from(r)))
        .orElseGet(() -> ResponseEntity.status(404).body(ErrorBody.of("NOT_FOUND")));
  }

  @PostMapping("/{efId}/accept")
  public DecisionResponse accept(
      HttpServletRequest request,
      @PathVariable String efId,
      @RequestBody(required = false) String body) {
    return decide(request, efId, body, DecisionType.ACCEPT);
  }

  @PostMapping("/{efId}/decline")
  public DecisionResponse decline(
      HttpServletRequest request,
      @PathVariable String efId,
      @RequestBody(required = false) String body) {
    return decide(request, efId, body, DecisionType.DECLINE);
  }

  private DecisionResponse decide(
      HttpServletRequest request, String efId, String body, DecisionType type) {
    Caller caller = callers.resolve(request);
    return decisions.decide(caller, new EfId(efId), type, body);
  }

  static int clamp(String size) {
    if (size == null || size.isBlank()) {
      return DEFAULT_SIZE;
    }
    try {
      return Math.max(1, Math.min(MAX_SIZE, Integer.parseInt(size.strip())));
    } catch (NumberFormatException e) {
      throw new BadRequestException("size must be a number");
    }
  }
}
