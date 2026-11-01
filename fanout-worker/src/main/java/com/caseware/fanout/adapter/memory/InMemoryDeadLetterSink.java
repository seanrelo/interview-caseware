package com.caseware.fanout.adapter.memory;

import com.caseware.fanout.domain.DeadLetter;
import com.caseware.fanout.port.DeadLetterSink;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory DLQ. Each entry carries its timestamp ({@code DeadLetter.at}) and is retained for
 * {@code retention} (NFR-29, 14 days to agree); expired entries are dropped lazily on read.
 */
public class InMemoryDeadLetterSink implements DeadLetterSink {

  public static final Duration DEFAULT_RETENTION = Duration.ofDays(14);

  private final List<DeadLetter> entries = new CopyOnWriteArrayList<>();
  private final Clock clock;
  private final Duration retention;

  public InMemoryDeadLetterSink() {
    this(Clock.systemUTC(), DEFAULT_RETENTION);
  }

  public InMemoryDeadLetterSink(Clock clock, Duration retention) {
    this.clock = clock;
    this.retention = retention;
  }

  @Override
  public void put(DeadLetter deadLetter) {
    entries.add(deadLetter);
  }

  @Override
  public int depth() {
    return entries().size();
  }

  public Duration retention() {
    return retention;
  }

  public Instant expiresAt(DeadLetter deadLetter) {
    return deadLetter.at().plus(retention);
  }

  /** Entries not yet past retention. */
  public List<DeadLetter> entries() {
    Instant now = clock.instant();
    entries.removeIf(e -> !expiresAt(e).isAfter(now));
    return List.copyOf(entries);
  }
}
