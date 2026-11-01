package com.caseware.fanout.domain;

public enum ItemState {
  PENDING,
  LEASED,
  DONE,
  SKIPPED,
  DEAD_LETTERED;

  public boolean terminal() {
    return this == DONE || this == SKIPPED || this == DEAD_LETTERED;
  }
}
