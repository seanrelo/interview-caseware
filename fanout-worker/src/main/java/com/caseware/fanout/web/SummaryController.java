package com.caseware.fanout.web;

import com.caseware.fanout.domain.EfId;
import com.caseware.fanout.status.Caller;
import com.caseware.fanout.summary.SummaryQuery;
import com.caseware.fanout.summary.SummaryView;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Summary of one pending EF. Any failure of the summary side is a 200 UNAVAILABLE, never a 5xx,
 * and cannot change the status row (FR-42, NFR-30).
 */
@RestController
@RequestMapping("/api/v1/pending-updates")
public class SummaryController {

  private final CallerResolver callers;
  private final SummaryQuery summaries;

  public SummaryController(CallerResolver callers, SummaryQuery summaries) {
    this.callers = callers;
    this.summaries = summaries;
  }

  @GetMapping("/{efId}/summary")
  public ResponseEntity<?> summary(HttpServletRequest request, @PathVariable String efId) {
    Caller caller = callers.resolve(request);
    Optional<SummaryView> view;
    try {
      view = summaries.summaryFor(caller, new EfId(efId));
    } catch (RuntimeException e) {
      return ResponseEntity.ok(SummaryBody.unavailable());
    }
    if (view.isEmpty()) {
      return ResponseEntity.status(404).body(ErrorBody.of("NOT_FOUND"));
    }
    try {
      return ResponseEntity.ok(SummaryBody.from(view.get()));
    } catch (RuntimeException e) {
      return ResponseEntity.ok(SummaryBody.unavailable());
    }
  }
}
