package com.caseware.fanout.summary;

/** A stored summary no longer matches its recorded content hash (NFR-15). */
public class SummaryTamperedException extends RuntimeException {
  public SummaryTamperedException(String message) {
    super(message);
  }
}
