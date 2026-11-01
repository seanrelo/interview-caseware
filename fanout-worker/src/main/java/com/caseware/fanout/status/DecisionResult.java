package com.caseware.fanout.status;

public enum DecisionResult {
  RECORDED,
  /** The same (EF, template, version, type) was already recorded; nothing changed. */
  DUPLICATE
}
