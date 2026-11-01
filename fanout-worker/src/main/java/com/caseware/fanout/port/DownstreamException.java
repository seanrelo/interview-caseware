package com.caseware.fanout.port;

/** Unchecked failure of the downstream marker call. Retryable (FR-18). */
public class DownstreamException extends RuntimeException {
  public DownstreamException(String message) {
    super(message);
  }

  public DownstreamException(String message, Throwable cause) {
    super(message, cause);
  }
}
