package com.caseware.fanout.status;

/** Per-firm totals by status, maintained per write (ADR 0008). */
public record StatusCounts(long upToDate, long updatePending, long unknown) {
  public long total() {
    return upToDate + updatePending + unknown;
  }
}
