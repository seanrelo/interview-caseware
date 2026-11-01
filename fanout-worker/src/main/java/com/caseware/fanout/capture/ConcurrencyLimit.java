package com.caseware.fanout.capture;

/**
 * Maximum concurrent EF loads for backfill (NFR-4). Separate from the fan-out limiter. Changeable
 * at runtime; a new value takes effect for the next load that is started.
 */
public interface ConcurrencyLimit {
  int limit();

  /** @throws IllegalArgumentException if n is below 1 */
  void setLimit(int n);
}
