package com.caseware.fanout.web;

/**
 * A decision for a version that is not the row's pending target, or on a row that is not
 * UPDATE_PENDING (up to date or unknown). Mapped to 422 STALE_OR_NOT_PENDING; nothing is recorded.
 */
public class StaleOrNotPendingException extends RuntimeException {
  public StaleOrNotPendingException() {
    super("stale or not pending", null, false, false);
  }
}
