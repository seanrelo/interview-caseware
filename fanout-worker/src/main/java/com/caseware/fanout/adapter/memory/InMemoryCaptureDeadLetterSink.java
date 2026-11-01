package com.caseware.fanout.adapter.memory;

import com.caseware.fanout.capture.CaptureDeadLetterSink;
import com.caseware.fanout.capture.CaptureMessage;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/** Keeps every dead-lettered capture. Retention is a concern of the real adapter (NFR-29). */
public class InMemoryCaptureDeadLetterSink implements CaptureDeadLetterSink {

  public record Entry(CaptureMessage message, String reason, Instant at) {}

  private final Clock clock;
  private final List<Entry> entries = new CopyOnWriteArrayList<>();

  public InMemoryCaptureDeadLetterSink(Clock clock) {
    this.clock = Objects.requireNonNull(clock);
  }

  @Override
  public void deadLetter(CaptureMessage message, String reason) {
    entries.add(new Entry(message, reason, clock.instant()));
  }

  public List<Entry> entries() {
    return List.copyOf(entries);
  }

  public int size() {
    return entries.size();
  }
}
