package com.caseware.fanout.capture;

import com.caseware.fanout.capture.BackfillMetrics.Counter;
import com.caseware.fanout.domain.EfRef;
import com.caseware.fanout.domain.Page;
import com.caseware.fanout.domain.Region;
import com.caseware.fanout.limiter.RetryPolicy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.random.RandomGenerator;
import com.caseware.fanout.port.FirmFlags;

/**
 * Rate-limited backfill of template-version records (FR-6, FR-7, ADR 0003). The only component
 * allowed to call {@link EfLoader} (NFR-22).
 *
 * <p>The job never sleeps and starts no thread of its own for scheduling: the caller invokes
 * {@link #step} or {@link #run} and moves time on (an injected {@link Clock}). Inside a step, loads
 * run on virtual threads with at most {@link ConcurrencyLimit#limit()} in flight; the limit is read
 * again before every load. A failed load writes nothing, so the EF stays record-less, and is
 * retried with exponential backoff and full jitter, without an attempt cap (FR-7): a failing EF is
 * never given up on and never recorded as "up to date". Calls are serialised per job; the work
 * inside one step is concurrent.
 *
 * <p>The load start instant is the capture timestamp: a hook capture that lands during the long
 * load carries a later timestamp and wins (FR-5).
 *
 * <p>A flag-off EF is deferred, not forgotten (NFR-42): it stays in a deferred set that every step
 * re-checks with the cheap flag lookup (no load while off). When the firm flag turns on it goes
 * through the normal path (re-check record, load, upsert). {@code SKIPPED_FLAG_OFF} counts each
 * deferral once per EF until it is processed. A failure of the flag lookup or of the record read
 * is handled like a failed load: the EF is scheduled for retry and never dropped (FR-7).
 */
public final class BackfillJob {

  /** Settings: enumeration page size and the retry backoff window. */
  public record Config(int pageSize, Duration retryBase, Duration retryMax) {
    public Config {
      if (pageSize < 1) {
        throw new IllegalArgumentException("pageSize must be >= 1");
      }
      Objects.requireNonNull(retryBase);
      Objects.requireNonNull(retryMax);
    }

    public static Config defaults() {
      return new Config(500, Duration.ofSeconds(30), Duration.ofHours(1));
    }
  }

  /** Progress after one step. */
  public record StepResult(
      int handled,
      boolean enumerationDone,
      int pendingRetries,
      Optional<Instant> nextRetryAt,
      int deferred) {}

  private record Retry(EfRef ef, int attempt, Instant dueAt) {}

  private static final class RegionState {
    Optional<String> token = Optional.empty();
    boolean enumerationDone;
    final Deque<EfRef> pending = new ArrayDeque<>();
    final List<Retry> retries = new ArrayList<>();
    /** Flag-off EFs waiting for their firm to be enabled, with the attempt they had. */
    final Map<EfRef, Integer> deferred = new LinkedHashMap<>();
  }

  private final EfCatalog catalog;
  private final EfLoader loader;
  private final TemplateVersionRecordStore store;
  private final FirmFlags flags;
  private final ConcurrencyLimit limit;
  private final Clock clock;
  private final RandomGenerator random;
  private final BackfillMetrics metrics;
  private final Config config;
  private final RetryPolicy backoff;

  private final Object stepLock = new Object();
  private final Object mon = new Object(); // guards inflight and region state touched by workers
  private final Map<Region, RegionState> states = new EnumMap<>(Region.class);
  private int inflight;
  private volatile boolean paused;

  public BackfillJob(
      EfCatalog catalog,
      EfLoader loader,
      TemplateVersionRecordStore store,
      FirmFlags flags,
      ConcurrencyLimit limit,
      Clock clock,
      RandomGenerator random,
      BackfillMetrics metrics,
      Config config) {
    this.catalog = Objects.requireNonNull(catalog);
    this.loader = Objects.requireNonNull(loader);
    this.store = Objects.requireNonNull(store);
    this.flags = Objects.requireNonNull(flags);
    this.limit = Objects.requireNonNull(limit);
    this.clock = Objects.requireNonNull(clock);
    this.random = Objects.requireNonNull(random);
    this.metrics = Objects.requireNonNull(metrics);
    this.config = Objects.requireNonNull(config);
    this.backoff = new RetryPolicy(Integer.MAX_VALUE, config.retryBase(), config.retryMax());
    for (Region r : Region.values()) {
      states.put(r, new RegionState());
    }
  }

  /** Stops starting new loads; loads already in flight finish. Loses nothing. */
  public void pause() {
    paused = true;
  }

  public void resume() {
    paused = false;
  }

  public boolean isPaused() {
    return paused;
  }

  /** Steps until nothing is left to do now: paused, finished, or only retries not yet due. */
  public StepResult run(Region region) {
    StepResult r;
    do {
      r = step(region);
    } while (r.handled() > 0 && !paused);
    return r;
  }

  /**
   * One cycle: the retries that are due, then the rest of the current page (fetching the next page
   * when it is empty). Blocks until all loads started in this step have finished.
   */
  public StepResult step(Region region) {
    Objects.requireNonNull(region);
    synchronized (stepLock) {
      RegionState st = states.get(region);
      List<Object[]> work = new ArrayList<>(); // {EfRef, Integer attempt}
      synchronized (mon) {
        if (paused) {
          return snapshot(st);
        }
        Instant now = clock.instant();
        List<Retry> due = new ArrayList<>();
        for (Retry r : st.retries) {
          if (!r.dueAt().isAfter(now)) {
            due.add(r);
          }
        }
        st.retries.removeAll(due);
        for (Retry r : due) {
          work.add(new Object[] {r.ef(), r.attempt()});
        }
        // deferred flag-off EFs: cheap flag check only; re-enabled ones take the normal path
        var it = st.deferred.entrySet().iterator();
        while (it.hasNext()) {
          var d = it.next();
          boolean on;
          try {
            on = flags.enabled(d.getKey().firm());
          } catch (RuntimeException e) {
            continue; // cannot tell: keep it deferred, look again next step
          }
          if (on) {
            work.add(new Object[] {d.getKey(), d.getValue()});
            it.remove();
          }
        }
      }
      fillFromCatalog(region, st);
      synchronized (mon) {
        while (!st.pending.isEmpty()) {
          work.add(new Object[] {st.pending.pollFirst(), 0});
        }
      }
      int handled = dispatch(region, st, work);
      synchronized (mon) {
        return new StepResult(
            handled, st.enumerationDone, st.retries.size(), nextRetry(st), st.deferred.size());
      }
    }
  }

  public StepResult status(Region region) {
    synchronized (mon) {
      return snapshot(states.get(region));
    }
  }

  private StepResult snapshot(RegionState st) {
    return new StepResult(
        0, st.enumerationDone, st.retries.size(), nextRetry(st), st.deferred.size());
  }

  private Optional<Instant> nextRetry(RegionState st) {
    return st.retries.stream().map(Retry::dueAt).min(Instant::compareTo);
  }

  /** Loads the next non-empty page into pending, unless enumeration is done or retries only. */
  private void fillFromCatalog(Region region, RegionState st) {
    synchronized (mon) {
      if (st.enumerationDone || !st.pending.isEmpty()) {
        return;
      }
    }
    while (true) {
      Optional<String> token;
      synchronized (mon) {
        token = st.token;
      }
      Page<EfRef> page = catalog.listEfs(region, token, config.pageSize());
      synchronized (mon) {
        for (EfRef ef : page.items()) {
          st.pending.addLast(ef);
          metrics.count(Counter.ENUMERATED, region);
        }
        st.token = page.nextToken();
        if (page.nextToken().isEmpty()) {
          st.enumerationDone = true;
        }
        if (!st.pending.isEmpty() || st.enumerationDone) {
          return;
        }
      }
    }
  }

  private int dispatch(Region region, RegionState st, List<Object[]> work) {
    int handled = 0;
    int i = 0;
    for (; i < work.size(); i++) {
      EfRef ef = (EfRef) work.get(i)[0];
      int attempt = (Integer) work.get(i)[1];
      synchronized (mon) {
        // wait for a free slot; the limit is re-read each time (runtime changeable)
        while (inflight >= limit.limit() && !paused) {
          waitMon();
        }
        if (paused) {
          break;
        }
        try {
          if (!flags.enabled(ef.firm())) {
            if (st.deferred.putIfAbsent(ef, attempt) == null) {
              metrics.count(Counter.SKIPPED_FLAG_OFF, region);
            }
            handled++;
            continue;
          }
          if (store.find(region, ef).isPresent()) {
            metrics.count(Counter.SKIPPED_HOOK_COVERED, region);
            handled++;
            continue;
          }
        } catch (RuntimeException e) {
          // flag or store read failed: same as a failed load, the EF is never dropped (FR-7)
          scheduleRetry(region, st, ef, attempt + 1);
          handled++;
          continue;
        }
        inflight++;
        metrics.count(Counter.ATTEMPTED, region);
      }
      handled++;
      int nextAttempt = attempt + 1;
      Thread.ofVirtual().start(() -> load(region, st, ef, nextAttempt));
    }
    synchronized (mon) {
      // paused mid-batch: keep the unstarted EFs (retries keep their attempt, as already due)
      for (; i < work.size(); i++) {
        EfRef ef = (EfRef) work.get(i)[0];
        int attempt = (Integer) work.get(i)[1];
        if (attempt == 0) {
          st.pending.addLast(ef);
        } else {
          st.retries.add(new Retry(ef, attempt, clock.instant()));
        }
      }
      while (inflight > 0) {
        waitMon();
      }
    }
    return handled;
  }

  private void load(Region region, RegionState st, EfRef ef, int attempt) {
    Instant startedAt = clock.instant();
    try {
      TemplateVersionRef ref = loader.load(region, ef);
      UpsertResult result =
          store.upsertVersion(
              region, ef, ref.template(), ref.version(), startedAt, CaptureSource.BACKFILL);
      // not APPLIED: a hook capture with a later timestamp won the write, so the EF is covered
      metrics.count(
          result == UpsertResult.APPLIED ? Counter.RECORDED : Counter.SKIPPED_HOOK_COVERED, region);
    } catch (Exception e) {
      // nothing was written: the EF stays record-less (FR-7); never a placeholder
      synchronized (mon) {
        scheduleRetry(region, st, ef, attempt);
      }
    } finally {
      synchronized (mon) {
        inflight--;
        mon.notifyAll();
      }
    }
  }

  /** Caller holds {@code mon}. */
  private void scheduleRetry(Region region, RegionState st, EfRef ef, int attempt) {
    metrics.count(Counter.FAILED, region);
    Duration delay = backoff.delay(attempt, random);
    st.retries.add(new Retry(ef, attempt, clock.instant().plus(delay)));
    metrics.count(Counter.RETRY_SCHEDULED, region);
  }

  private void waitMon() {
    try {
      mon.wait();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("backfill step interrupted", e);
    }
  }
}
