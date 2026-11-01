package com.caseware.fanout.adapter.memory;

import com.caseware.fanout.capture.CaptureDeadLetterSink;
import com.caseware.fanout.capture.CaptureMessage;
import com.caseware.fanout.capture.CaptureMetrics;
import com.caseware.fanout.capture.CaptureOutbox;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded in-memory outbox. A claim and a nack both just push the message's visibility time
 * forward; ack removes. The attempt counter counts failed deliveries (nacks) only: a lease that
 * expires because the relay crashed is not the message's fault and does not count. When the
 * counter reaches {@code maxAttempts} the message is handed to the dead-letter sink first and
 * removed second, so a failing sink keeps the message in the outbox.
 */
public class InMemoryCaptureOutbox implements CaptureOutbox {

  private static final class Entry {
    CaptureMessage message;
    Instant visibleAt;

    Entry(CaptureMessage message, Instant visibleAt) {
      this.message = message;
      this.visibleAt = visibleAt;
    }
  }

  private final Clock clock;
  private final int maxAttempts;
  private final int capacity;
  private final CaptureDeadLetterSink deadLetters;
  private final CaptureMetrics metrics;
  private final Map<String, Entry> entries = new LinkedHashMap<>();

  public InMemoryCaptureOutbox(
      Clock clock,
      int maxAttempts,
      int capacity,
      CaptureDeadLetterSink deadLetters,
      CaptureMetrics metrics) {
    if (maxAttempts <= 0 || capacity <= 0) {
      throw new IllegalArgumentException("maxAttempts and capacity must be > 0");
    }
    this.clock = Objects.requireNonNull(clock);
    this.maxAttempts = maxAttempts;
    this.capacity = capacity;
    this.deadLetters = Objects.requireNonNull(deadLetters);
    this.metrics = Objects.requireNonNull(metrics);
  }

  @Override
  public synchronized void append(CaptureMessage message) {
    Objects.requireNonNull(message);
    if (entries.containsKey(message.id())) {
      return;
    }
    if (entries.size() >= capacity) {
      throw new IllegalStateException("capture outbox full (" + capacity + ")");
    }
    entries.put(message.id(), new Entry(message, Instant.MIN));
  }

  @Override
  public synchronized List<CaptureMessage> claimBatch(int max, Duration lease) {
    Instant now = clock.instant();
    List<CaptureMessage> out = new ArrayList<>();
    for (Entry e : entries.values()) {
      if (out.size() == max) {
        break;
      }
      if (!e.visibleAt.isAfter(now)) {
        e.visibleAt = now.plus(lease);
        out.add(e.message);
      }
    }
    return out;
  }

  @Override
  public synchronized void ack(String id) {
    entries.remove(id);
  }

  @Override
  public void nack(String id, Duration delay) {
    nack(id, delay, null);
  }

  @Override
  public synchronized void nack(String id, Duration delay, String reason) {
    Entry e = entries.get(id);
    if (e == null) {
      return;
    }
    CaptureMessage failed = e.message.withAttempt(e.message.attempt() + 1);
    if (failed.attempt() >= maxAttempts) {
      String why = "exhausted after " + failed.attempt() + " failed deliveries";
      deadLetters.deadLetter(failed, reason == null ? why : reason + ": " + why);
      entries.remove(id);
      metrics.count(CaptureMetrics.Event.DEAD_LETTERED);
      return;
    }
    e.message = failed;
    e.visibleAt = clock.instant().plus(delay);
  }

  /** Messages still in the outbox (visible, leased or backing off). */
  public synchronized int size() {
    return entries.size();
  }
}
