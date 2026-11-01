package com.caseware.fanout.summary;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

public interface SummaryJobQueue {
  /** Idempotent per pair while a job for it is outstanding (queued, delayed or in flight). */
  void enqueue(PairKey pair, JobReason reason);

  /** Next job whose not-before time has passed; it stays outstanding until ack, retryLater or deadLetter. */
  Optional<SummaryJob> poll();

  void ack(SummaryJob job);

  /** Re-queues with attempt + 1, reason RETRY, not before now + delay. */
  void retryLater(SummaryJob job, Duration delay);

  /** Additive: re-queues the same job (same attempt and reason, no attempt burned) not before now + delay. */
  void defer(SummaryJob job, Duration delay);

  /** Moves to the dead-letter list with the cause and the current time; the pair is no longer outstanding. */
  void deadLetter(SummaryJob job, String cause);

  /** Outstanding jobs (queued, delayed, in flight). */
  int depth();

  List<SummaryDeadLetter> deadLetters();
}
