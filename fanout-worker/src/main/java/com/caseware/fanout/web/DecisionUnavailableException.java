package com.caseware.fanout.web;

/** The decision could not be recorded (store failure). Mapped to 503 DECISION_UNAVAILABLE. */
public class DecisionUnavailableException extends RuntimeException {
  public DecisionUnavailableException(Throwable cause) {
    super("DECISION_UNAVAILABLE", cause, false, false);
  }
}
