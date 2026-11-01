package com.caseware.fanout.port;

/** The downstream signalled throttling; drives the AIMD decrease (NFR-9). */
public class DownstreamThrottledException extends DownstreamException {
  public DownstreamThrottledException(String message) {
    super(message);
  }
}
