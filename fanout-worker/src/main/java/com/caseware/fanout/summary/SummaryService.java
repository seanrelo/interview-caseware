package com.caseware.fanout.summary;

import com.caseware.fanout.domain.TemplateId;
import com.caseware.fanout.domain.VersionId;
import com.caseware.fanout.graph.EligibleResult;
import com.caseware.fanout.graph.GraphPublishEvent;
import com.caseware.fanout.graph.VersionGraph;
import com.caseware.fanout.status.VersionGraphProvider;
import java.time.Clock;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Generates, stores and reuses one summary per (template, baseline, target) pair, shared across
 * firms (FR-36). The model sees only the pinned model, prompt version and the diff (FR-37, NFR-37).
 * Any failure or rejection stores nothing (FR-42) and retries with capped exponential backoff
 * until the dead-letter queue (NFR-29, NFR-30). Nothing here starts a thread: a caller invokes
 * {@link #processNext()}.
 */
public final class SummaryService {
  private final SummaryStore store;
  private final DiffSource diffs;
  private final ModelClient model;
  private final SummaryValidator validator;
  private final SingleFlight singleFlight;
  private final SummaryJobQueue queue;
  private final BaselinesInUse baselines;
  private final VersionGraphProvider graphs;
  private final SummaryProperties props;
  private final SummaryMetrics metrics;
  private final Clock clock;

  /** Job creation time per outstanding pair, for the publish-to-ready latency (NFR-20). */
  private final Map<PairKey, Instant> requestedAt = new ConcurrentHashMap<>();

  private final AtomicInteger skippedBaselines = new AtomicInteger();

  public SummaryService(
      SummaryStore store,
      DiffSource diffs,
      ModelClient model,
      SummaryValidator validator,
      SingleFlight singleFlight,
      SummaryJobQueue queue,
      BaselinesInUse baselines,
      VersionGraphProvider graphs,
      SummaryProperties props,
      SummaryMetrics metrics,
      Clock clock) {
    this.store = Objects.requireNonNull(store, "store");
    this.diffs = Objects.requireNonNull(diffs, "diffs");
    this.model = Objects.requireNonNull(model, "model");
    this.validator = Objects.requireNonNull(validator, "validator");
    this.singleFlight = Objects.requireNonNull(singleFlight, "singleFlight");
    this.queue = Objects.requireNonNull(queue, "queue");
    this.baselines = Objects.requireNonNull(baselines, "baselines");
    this.graphs = Objects.requireNonNull(graphs, "graphs");
    this.props = Objects.requireNonNull(props, "props");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.clock = Objects.requireNonNull(clock, "clock");
  }
  /**
   * Ready if a current record exists (reuse, FR-36); Failed if the pair was dead-lettered (no new
   * job); otherwise an idempotent job is enqueued and Generating returned. A tampered or unreadable store propagates (never turned into a summary).
   */
  public SummaryOutcome ensure(PairKey pair, JobReason reason) {
    Optional<SummaryRecord> current = store.current(pair);
    if (current.isPresent()) {
      return new SummaryOutcome.Ready(current.get());
    }
    Optional<SummaryDeadLetter> dead = deadLetterOf(pair);
    if (dead.isPresent()) {
      // Exhausted retries: degrade to unavailable (NFR-30); an operator redrive, not every page load, retries.
      return new SummaryOutcome.Failed(FailureKind.MODEL_ERROR, "dead-lettered: " + dead.get().cause());
    }
    request(pair, reason);
    return new SummaryOutcome.Generating();
  }

  private Optional<SummaryDeadLetter> deadLetterOf(PairKey pair) {
    return queue.deadLetters().stream().filter(d -> d.job().pair().equals(pair)).findFirst();
  }

  public Optional<SummaryRecord> find(PairKey pair) {
    return store.current(pair);
  }

  /** Takes one due job. Returns 1 if a job was taken from the queue (whatever its result), else 0. */
  public int processNext() {
    Optional<SummaryJob> polled = queue.poll();
    if (polled.isEmpty()) {
      return 0;
    }
    SummaryJob job = polled.get();
    PairKey pair = job.pair();
    Optional<Lease> lease = singleFlight.tryAcquire(pair, props.leaseTtl());
    if (lease.isEmpty()) {
      // Another worker holds the lease: no model call, no attempt burned. Do not drop the job: if the
      // holder died, this job is what produces the summary once the lease expires (FR-35, NFR-20).
      queue.defer(job, props.baseBackoff());
      return 1;
    }
    try {
      Optional<SummaryRecord> existing = store.current(pair);
      if (existing.isPresent()) {
        requestedAt.remove(pair);
        queue.ack(job);
        return 1;
      }
      // Deterministic id: a retry or a late worker re-appends the SAME record, so history never
      // holds an orphan for the pair (NFR-27). A record already stored but not current is reused.
      String id = generationId(pair, 1);
      SummaryRecord record = store.get(pair, id).orElse(null);
      if (record == null) {
        record = store.append(generate(pair, id));
      }
      boolean moved = store.moveCurrentIfAbsent(pair, record.generationId());
      Instant started = requestedAt.remove(pair);
      if (moved) {
        metrics.generated(pair);
        if (started != null) {
          metrics.readyLatency(Duration.between(started, clock.instant()));
        }
      }
      queue.ack(job);
    } catch (GenerationFailure f) {
      fail(job, f.kind, f.getMessage());
    } catch (Throwable t) {
      // Exceptions and non-fatal Errors alike must not strand the pair (NFR-30, NFR-32).
      fail(job, FailureKind.STORE_ERROR, String.valueOf(t));
      if (t instanceof VirtualMachineError vme) {
        throw vme;
      }
    } finally {
      singleFlight.release(lease.get());
    }
    return 1;
  }

  /**
   * Explicit supersede (ADR 0010, clarification 1): generates synchronously, appends a NEW record
   * and moves the pointer. The old record stays readable in the history. Generating if another
   * worker holds the lease; Failed (nothing stored, no retry) on any failure.
   */
  public SummaryOutcome supersede(PairKey pair) {
    Optional<Lease> lease = singleFlight.tryAcquire(pair, props.leaseTtl());
    if (lease.isEmpty()) {
      return new SummaryOutcome.Generating();
    }
    try {
      SummaryRecord record = generate(pair, generationId(pair, store.history(pair).size() + 1));
      SummaryRecord stored = store.append(record);
      store.supersede(pair, stored.generationId());
      metrics.generated(pair);
      return new SummaryOutcome.Ready(stored);
    } catch (GenerationFailure f) {
      metrics.failed(f.kind);
      return new SummaryOutcome.Failed(f.kind, f.getMessage());
    } catch (Throwable t) {
      metrics.failed(FailureKind.STORE_ERROR);
      if (t instanceof VirtualMachineError vme) {
        throw vme;
      }
      return new SummaryOutcome.Failed(FailureKind.STORE_ERROR, String.valueOf(t));
    } finally {
      singleFlight.release(lease.get());
    }
  }

  /**
   * Pre-generates for the baselines in use whose eligible target is the new version (FR-35). A
   * baseline whose graph query fails is skipped and counted, never aborting the loop.
   */
  public void onPublished(GraphPublishEvent event) {
    TemplateId template = event.templateId();
    VersionId version = event.version();
    VersionGraph graph = graphs.snapshot();
    for (VersionId baseline : baselines.baselines(template)) {
      if (baseline.equals(version)) {
        continue;
      }
      try {
        EligibleResult result = graph.eligibleVersion(template, baseline);
        if (result instanceof EligibleResult.Eligible e && e.version().equals(version)) {
          PairKey pair = new PairKey(template, baseline, version);
          if (!hasCurrentQuietly(pair)) {
            request(pair, JobReason.PUBLISH);
          }
        } else if (result instanceof EligibleResult.Unknown) {
          skippedBaselines.incrementAndGet();
        }
      } catch (RuntimeException ex) {
        skippedBaselines.incrementAndGet();
      }
    }
  }

  /** Baselines skipped because the graph could not answer for them. */
  public int skippedBaselines() {
    return skippedBaselines.get();
  }

  private boolean hasCurrentQuietly(PairKey pair) {
    try {
      return store.current(pair).isPresent();
    } catch (RuntimeException e) {
      return false;
    }
  }

  private void request(PairKey pair, JobReason reason) {
    requestedAt.putIfAbsent(pair, clock.instant());
    queue.enqueue(pair, reason);
  }

  private void fail(SummaryJob job, FailureKind kind, String message) {
    metrics.failed(kind);
    if (job.attempt() >= props.maxAttempts()) {
      requestedAt.remove(job.pair());
      queue.deadLetter(job, kind + ": " + message);
    } else {
      queue.retryLater(job, backoff(job.attempt()));
    }
  }

  /** base * 2^(attempt-1), capped at maxBackoff; no jitter, so it is reproducible. */
  Duration backoff(int attempt) {
    Duration d = props.baseBackoff();
    for (int i = 1; i < attempt; i++) {
      d = d.multipliedBy(2);
      if (d.compareTo(props.maxBackoff()) >= 0) {
        return props.maxBackoff();
      }
    }
    return d.compareTo(props.maxBackoff()) > 0 ? props.maxBackoff() : d;
  }

  /** Builds a validated record; stores nothing. */
  private SummaryRecord generate(PairKey pair, String generationId) {
    Diff diff;
    try {
      diff = diffs.diff(pair).orElse(null);
    } catch (Throwable t) {
      rethrowFatal(t);
      throw new GenerationFailure(FailureKind.DIFF_UNAVAILABLE, "diff lookup failed: " + t);
    }
    if (diff == null || !diff.pair().equals(pair)) {
      throw new GenerationFailure(FailureKind.DIFF_UNAVAILABLE, "no diff for " + pair);
    }
    ModelOutput out;
    try {
      out = model.generate(new ModelRequest(props.pinnedModel(), props.prompt(), diff));
    } catch (Throwable t) {
      rethrowFatal(t);
      throw new GenerationFailure(FailureKind.MODEL_ERROR, "model call failed: " + t);
    }
    ValidationResult result = validator.validate(diff, out.statements());
    if (result instanceof ValidationResult.Valid valid) {
      return SummaryRecord.create(
          generationId, pair, valid.statements(), diff, props.pinnedModel(), props.prompt(), clock.instant());
    }
    throw new GenerationFailure(
        FailureKind.REJECTED, "rejected: " + ((ValidationResult.Rejected) result).violations());
  }

  private static void rethrowFatal(Throwable t) {
    if (t instanceof VirtualMachineError vme) {
      throw vme;
    }
  }

  /** Stable per (pair, ordinal): ordinal 1 is the first record, a supersede uses history size + 1. */
  static String generationId(PairKey pair, int ordinal) {
    try {
      String key = pair.template() + "|" + pair.baseline() + "|" + pair.target();
      byte[] d = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
      return "gen-" + HexFormat.of().formatHex(d, 0, 8) + "-" + ordinal;
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static final class GenerationFailure extends RuntimeException {
    private final FailureKind kind;

    GenerationFailure(FailureKind kind, String message) {
      super(message, null, false, false);
      this.kind = kind;
    }
  }
}
