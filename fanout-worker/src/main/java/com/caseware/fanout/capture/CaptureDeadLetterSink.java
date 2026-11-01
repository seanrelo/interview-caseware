package com.caseware.fanout.capture;

/** Keeps captures that exhausted their attempts (NFR-29). A capture here is never "no update". */
public interface CaptureDeadLetterSink {
  void deadLetter(CaptureMessage message, String reason);
}
