package com.caseware.fanout.app;

import com.caseware.fanout.domain.WorkItem;
import java.util.Optional;

/** Seam for NFR-8/9/11 (T5): permit before each downstream call, released with the outcome. */
public interface CallGate {
  /** Unlimited gate used until the adaptive limiter is installed. */
  CallGate UNLIMITED = item -> Optional.of(outcome -> {});

  enum Outcome {
    SUCCESS,
    THROTTLED,
    ERROR
  }

  interface Permit {
    void release(Outcome outcome);
  }

  /** Empty if no capacity now; the item is then rescheduled without consuming an attempt. */
  Optional<Permit> tryAcquire(WorkItem item);
}
