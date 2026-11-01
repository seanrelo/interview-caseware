package com.caseware.fanout.port;

public enum MarkResult {
  /** The stored pending target changed. */
  APPLIED,
  /** Stored state already equal or newer; nothing changed (FR-17). */
  UNCHANGED
}
