package com.caseware.fanout.summary;

/** The diff could not be fetched (failure, not "no diff"). Callers retry; never treated as no update. */
public class DiffUnavailableException extends RuntimeException {
  public DiffUnavailableException(String message) {
    super(message);
  }

  public DiffUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
