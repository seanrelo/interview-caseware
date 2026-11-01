package com.caseware.fanout.capture;

import java.time.Duration;
import java.util.List;

/**
 * Durable local outbox of the EF system (ADR 0002 option 2). Append is the only cost a hook pays
 * (NFR-2). Delivery is at-least-once: a claimed message that is neither acked nor nacked becomes
 * visible again when its lease expires (NFR-32).
 */
public interface CaptureOutbox {

  /** Idempotent on {@link CaptureMessage#id()}. May throw when the outbox is unavailable or full. */
  void append(CaptureMessage message);

  /** Claims up to {@code max} visible messages in append order and hides them for {@code lease}. */
  List<CaptureMessage> claimBatch(int max, Duration lease);

  void ack(String id);

  /**
   * Records a failed delivery and retries after {@code delay}. After the configured number of
   * failed deliveries the message goes to a {@link CaptureDeadLetterSink} and leaves the outbox. It
   * is never dropped.
   */
  void nack(String id, Duration delay);

  /**
   * Same as {@link #nack(String, Duration)} with a reason kept for the dead-letter entry if this
   * delivery exhausts the attempts. The default drops the reason.
   */
  default void nack(String id, Duration delay, String reason) {
    nack(id, delay);
  }
}
