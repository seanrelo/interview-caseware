package com.caseware.fanout.status;

import com.caseware.fanout.domain.Page;
import java.util.Objects;

/** One page of a firm's rows with the firm totals (FR-22, NFR-14). */
public record StatusPage(Page<StatusRow> rows, StatusCounts totals) {
  public StatusPage {
    Objects.requireNonNull(rows, "rows");
    Objects.requireNonNull(totals, "totals");
  }
}
