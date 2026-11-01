package com.caseware.fanout.status;

import java.util.Objects;

/** A decision was refused before anything was recorded. */
public class DecisionRejectedException extends RuntimeException {

  public enum Reason {
    /** The EF belongs to another firm than the authenticated caller (NFR-33). */
    NOT_YOUR_FIRM,
    /** The firm flag is off; decisions are rejected so the feature is inert (NFR-41). */
    FEATURE_OFF,
    /** The version is not in the current version graph; nothing is recorded (FR-25, FR-23). */
    UNKNOWN_VERSION
  }

  private final Reason reason;

  public DecisionRejectedException(Reason reason) {
    super(Objects.requireNonNull(reason).name());
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
