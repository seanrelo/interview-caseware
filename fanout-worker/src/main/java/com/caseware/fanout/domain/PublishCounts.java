package com.caseware.fanout.domain;

/** Work item counts of one publish, read from the item store (FR-21). */
public record PublishCounts(long done, long skipped, long deadLettered, long nonTerminal) {

  public long terminal() {
    return done + skipped + deadLettered;
  }
}
