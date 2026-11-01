package com.caseware.fanout.adapter.memory;

import com.caseware.fanout.summary.Lease;
import com.caseware.fanout.summary.PairKey;
import com.caseware.fanout.summary.SingleFlight;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Conditional put on an injected clock; an expired lease is taken over. */
public class InMemorySingleFlight implements SingleFlight {
  private final Clock clock;
  private final Map<PairKey, Lease> leases = new ConcurrentHashMap<>();
  private final AtomicLong owners = new AtomicLong();

  public InMemorySingleFlight(Clock clock) {
    this.clock = clock;
  }

  @Override
  public Optional<Lease> tryAcquire(PairKey pair, Duration ttl) {
    Instant now = clock.instant();
    Lease candidate = new Lease(pair, "owner-" + owners.incrementAndGet(), now.plus(ttl));
    Lease won =
        leases.compute(
            pair, (k, held) -> held == null || !held.expiresAt().isAfter(now) ? candidate : held);
    return won == candidate ? Optional.of(candidate) : Optional.empty();
  }

  @Override
  public void release(Lease lease) {
    leases.remove(lease.pair(), lease);
  }
}
