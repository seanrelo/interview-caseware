package com.caseware.fanout.web;

import com.caseware.fanout.status.StatusRow;

/** One pending-update row as sent to the client. updatedAt is ISO-8601. */
public record RowDto(
    String ef,
    String template,
    String status,
    String baseline,
    String pendingTarget,
    String updatedAt,
    String source) {

  static RowDto from(StatusRow r) {
    return new RowDto(
        r.ef().value(),
        r.template().map(t -> t.value()).orElse(null),
        r.status().name(),
        r.baseline().map(v -> v.value()).orElse(null),
        r.pendingTarget().map(v -> v.value()).orElse(null),
        r.updatedAt().toString(),
        r.source().name());
  }
}
