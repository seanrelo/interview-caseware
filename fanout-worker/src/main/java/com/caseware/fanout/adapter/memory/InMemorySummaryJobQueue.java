package com.caseware.fanout.adapter.memory;

import com.caseware.fanout.summary.JobReason;
import com.caseware.fanout.summary.PairKey;
import com.caseware.fanout.summary.SummaryDeadLetter;
import com.caseware.fanout.summary.SummaryJob;
import com.caseware.fanout.summary.SummaryJobQueue;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Thread-safe. Outstanding = queued, delayed or in flight; one per pair. Bounded by distinct pairs. */
public class InMemorySummaryJobQueue implements SummaryJobQueue {
  private record Entry(SummaryJob job, Instant notBefore) {}

  private final Clock clock;
  private final LinkedList<Entry> waiting = new LinkedList<>();
  private final Set<PairKey> outstanding = new HashSet<>();
  private final List<SummaryDeadLetter> dead = new ArrayList<>();

  public InMemorySummaryJobQueue(Clock clock) {
    this.clock = clock;
  }

  @Override
  public synchronized void enqueue(PairKey pair, JobReason reason) {
    if (outstanding.add(pair)) {
      waiting.add(new Entry(new SummaryJob(pair, reason, 1), Instant.MIN));
    }
  }

  @Override
  public synchronized Optional<SummaryJob> poll() {
    Instant now = clock.instant();
    for (Iterator<Entry> it = waiting.iterator(); it.hasNext(); ) {
      Entry e = it.next();
      if (!e.notBefore().isAfter(now)) {
        it.remove();
        return Optional.of(e.job());
      }
    }
    return Optional.empty();
  }

  @Override
  public synchronized void ack(SummaryJob job) {
    outstanding.remove(job.pair());
  }

  @Override
  public synchronized void retryLater(SummaryJob job, Duration delay) {
    outstanding.add(job.pair());
    waiting.add(
        new Entry(new SummaryJob(job.pair(), JobReason.RETRY, job.attempt() + 1), clock.instant().plus(delay)));
  }

  @Override
  public synchronized void defer(SummaryJob job, Duration delay) {
    outstanding.add(job.pair());
    waiting.add(new Entry(job, clock.instant().plus(delay)));
  }

  @Override
  public synchronized void deadLetter(SummaryJob job, String cause) {
    outstanding.remove(job.pair());
    dead.add(new SummaryDeadLetter(job, cause, clock.instant()));
  }

  @Override
  public synchronized int depth() {
    return outstanding.size();
  }

  @Override
  public synchronized List<SummaryDeadLetter> deadLetters() {
    return List.copyOf(dead);
  }
}
