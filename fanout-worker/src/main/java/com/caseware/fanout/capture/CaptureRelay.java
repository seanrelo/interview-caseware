package com.caseware.fanout.capture;

import com.caseware.fanout.capture.CaptureMetrics.Event;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies outbox messages to the regional store, at-least-once (ADR 0002, 0014). Every outcome of
 * the idempotent store call, including the NOOP_* kinds except one, acks the message; an exception,
 * a timeout, or NOOP_NO_RECORD on a BASELINE message nacks it with exponential backoff and jitter
 * (NFR-30, NFR-32). The exception is the accept that finds no record yet (FR-4): the version
 * capture it depends on may be backing off after a transient failure, so the accept is retried
 * (each retry counts toward the outbox's max attempts) and dead-lettered with reason "no record
 * for accept" if the record never appears. It is never acked as success. This nack does not count
 * toward the circuit breaker: the store answered.
 *
 * <p>Timeout: measured with the injected Clock around the store call. A call that took longer than
 * the limit counts as failed (the write may have happened; the retry is a harmless duplicate). A
 * hard interrupt is the job of the real adapter's client timeout and is not simulated here.
 *
 * <p>Circuit breaker: after {@code breakerFailures} consecutive failures the relay stops calling the
 * store for {@code breakerOpenFor}, without claiming, so no attempts are burned. It then probes
 * with one message; success closes it, failure re-opens it.
 *
 * <p>Cost: an accept for an EF that was never captured (ADR 0002 clarification 3) now burns its
 * attempts and ends in the dead-letter sink instead of being acked silently.
 */
public final class CaptureRelay {

  private static final Logger LOG = LoggerFactory.getLogger(CaptureRelay.class);

  public record Config(
      int batchSize,
      Duration lease,
      Duration storeTimeout,
      int breakerFailures,
      Duration breakerOpenFor,
      Duration backoffBase,
      Duration backoffMax) {
    public Config {
      if (batchSize <= 0 || breakerFailures <= 0) {
        throw new IllegalArgumentException("batchSize and breakerFailures must be > 0");
      }
      Objects.requireNonNull(lease);
      Objects.requireNonNull(storeTimeout);
      Objects.requireNonNull(breakerOpenFor);
      Objects.requireNonNull(backoffBase);
      Objects.requireNonNull(backoffMax);
    }

    public static Config defaults() {
      return new Config(
          100,
          Duration.ofSeconds(30),
          Duration.ofSeconds(2),
          5,
          Duration.ofSeconds(30),
          Duration.ofSeconds(1),
          Duration.ofMinutes(1));
    }
  }

  private enum State {
    CLOSED,
    OPEN,
    HALF_OPEN
  }

  private final TemplateVersionRecordStore store;
  private final CaptureOutbox outbox;
  private final Clock clock;
  private final RandomGenerator random;
  private final CaptureMetrics metrics;
  private final Config cfg;

  private State state = State.CLOSED;
  private Instant openUntil = Instant.EPOCH;
  private int consecutiveFailures;

  public CaptureRelay(
      TemplateVersionRecordStore store,
      CaptureOutbox outbox,
      Clock clock,
      RandomGenerator random,
      CaptureMetrics metrics,
      Config cfg) {
    this.store = Objects.requireNonNull(store);
    this.outbox = Objects.requireNonNull(outbox);
    this.clock = Objects.requireNonNull(clock);
    this.random = Objects.requireNonNull(random);
    this.metrics = Objects.requireNonNull(metrics);
    this.cfg = Objects.requireNonNull(cfg);
  }

  /** One pass. Returns the number of messages acked. */
  public synchronized int drainOnce() {
    if (state == State.OPEN) {
      if (clock.instant().isBefore(openUntil)) {
        metrics.count(Event.BREAKER_SKIPPED);
        return 0;
      }
      state = State.HALF_OPEN;
    }
    List<CaptureMessage> batch =
        outbox.claimBatch(state == State.HALF_OPEN ? 1 : cfg.batchSize(), cfg.lease());
    int acked = 0;
    for (CaptureMessage m : batch) {
      Instant start = clock.instant();
      UpsertResult result;
      try {
        result = apply(m);
      } catch (RuntimeException e) {
        LOG.warn("capture apply failed for {}: {}", m.id(), e.toString());
        fail(m);
        if (state == State.OPEN) {
          break; // the rest stay leased and become visible again, no attempt burned
        }
        continue;
      }
      if (Duration.between(start, clock.instant()).compareTo(cfg.storeTimeout()) > 0) {
        metrics.count(Event.RELAY_TIMEOUT);
        fail(m);
        if (state == State.OPEN) {
          break;
        }
        continue;
      }
      if (m.kind() == CaptureMessage.Kind.BASELINE && result == UpsertResult.NOOP_NO_RECORD) {
        metrics.count(Event.RELAY_NO_RECORD);
        outbox.nack(m.id(), backoff(m.attempt()), "no record for accept");
        consecutiveFailures = 0; // the store is healthy; this is an ordering problem
        state = State.CLOSED;
        continue;
      }
      outbox.ack(m.id());
      metrics.count(Event.RELAY_APPLIED);
      acked++;
      consecutiveFailures = 0;
      state = State.CLOSED;
    }
    return acked;
  }

  public synchronized boolean breakerOpen() {
    return state == State.OPEN && clock.instant().isBefore(openUntil);
  }

  private UpsertResult apply(CaptureMessage m) {
    return switch (m.kind()) {
      case VERSION ->
          store.upsertVersion(m.region(), m.ef(), m.template(), m.version(), m.at(), m.source());
      case BASELINE ->
          store.upsertBaseline(m.region(), m.ef(), m.template(), m.version(), m.at());
    };
  }

  private void fail(CaptureMessage m) {
    metrics.count(Event.RELAY_FAILED);
    outbox.nack(m.id(), backoff(m.attempt()));
    consecutiveFailures++;
    if (state == State.HALF_OPEN || consecutiveFailures >= cfg.breakerFailures()) {
      state = State.OPEN;
      openUntil = clock.instant().plus(cfg.breakerOpenFor());
      consecutiveFailures = 0;
      metrics.count(Event.BREAKER_OPENED);
    }
  }

  /** Exponential, capped, jittered into [delay/2, delay]. */
  Duration backoff(int attempt) {
    long base = Math.max(1, cfg.backoffBase().toMillis());
    long max = Math.max(base, cfg.backoffMax().toMillis());
    long delay = Math.min(max, base << Math.min(attempt, 20));
    long half = delay / 2;
    return Duration.ofMillis(half + random.nextLong(delay - half + 1));
  }
}
