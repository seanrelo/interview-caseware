package com.caseware.fanout.web;

import java.util.List;

/** One page of rows with the firm totals. */
public record PageDto(List<RowDto> rows, String nextToken, Totals totals) {
  public record Totals(long upToDate, long updatePending, long unknown) {}
}
