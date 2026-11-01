package com.caseware.fanout.capture;

/**
 * Where a capture came from. {@link #ACCEPT} exists only on outbox messages; it is never stored as
 * the source of a record (ADR 0002 clarification 4).
 */
public enum CaptureSource {
  CREATE,
  OPEN,
  SAVE,
  ACCEPT,
  BACKFILL;

  /** Create and backfill may seed an absent baseline (ADR 0002 clarification 4). */
  public boolean seedsBaseline() {
    return this == CREATE || this == BACKFILL;
  }
}
