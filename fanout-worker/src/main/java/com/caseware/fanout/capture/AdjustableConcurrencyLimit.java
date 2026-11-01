package com.caseware.fanout.capture;

/** Thread-safe {@link ConcurrencyLimit} held in a volatile field. */
public final class AdjustableConcurrencyLimit implements ConcurrencyLimit {
  private volatile int limit;

  public AdjustableConcurrencyLimit(int initial) {
    setLimit(initial);
  }

  @Override
  public int limit() {
    return limit;
  }

  @Override
  public void setLimit(int n) {
    if (n < 1) {
      throw new IllegalArgumentException("limit must be >= 1");
    }
    this.limit = n;
  }
}
