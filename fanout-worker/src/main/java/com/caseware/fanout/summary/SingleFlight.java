package com.caseware.fanout.summary;

import java.time.Duration;
import java.util.Optional;

/** One generator per pair at a time (FR-36). An expired lease can be taken over. */
public interface SingleFlight {
  Optional<Lease> tryAcquire(PairKey pair, Duration ttl);

  /** Releases only if the lease is still held by its owner; otherwise a no-op. */
  void release(Lease lease);
}
