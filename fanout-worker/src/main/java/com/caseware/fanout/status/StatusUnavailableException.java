package com.caseware.fanout.status;

/** The status could not be read; callers show unknown or an error, never up to date (FR-25). */
public class StatusUnavailableException extends RuntimeException {
  public StatusUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
