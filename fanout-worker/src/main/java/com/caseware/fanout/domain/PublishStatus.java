package com.caseware.fanout.domain;

/** Progress of one publish (FR-21). Dead-lettered items are reported separately. */
public record PublishStatus(
    String publishId, long total, long done, long skipped, long deadLettered, boolean sealed) {

  public long terminal() {
    return done + skipped + deadLettered;
  }

  public boolean complete() {
    return sealed && terminal() == total;
  }
}
