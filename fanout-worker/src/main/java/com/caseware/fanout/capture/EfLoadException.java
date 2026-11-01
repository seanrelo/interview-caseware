package com.caseware.fanout.capture;

/** Loading an EF failed. The EF stays record-less and is retried (FR-7). */
public class EfLoadException extends Exception {
  public EfLoadException(String message) {
    super(message);
  }

  public EfLoadException(String message, Throwable cause) {
    super(message, cause);
  }
}
