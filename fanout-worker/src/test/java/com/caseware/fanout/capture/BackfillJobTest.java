package com.caseware.fanout.capture;

import static com.caseware.fanout.capture.CaptureRig.EU;
import static com.caseware.fanout.capture.CaptureRig.TPL;
import static com.caseware.fanout.capture.CaptureRig.ef;
import static com.caseware.fanout.capture.CaptureRig.firm;
import static com.caseware.fanout.capture.CaptureRig.t;
import static com.caseware.fanout.capture.CaptureRig.v;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.caseware.fanout.adapter.memory.InMemoryEfCatalog;
import com.caseware.fanout.adapter.memory.InMemoryEfLoader;
import com.caseware.fanout.adapter.memory.InMemoryPublishProgressStore;
import com.caseware.fanout.adapter.memory.InMemoryWorkItemStore;
import com.caseware.fanout.adapter.memory.RecordBackedTemplateVersionIndex;
import com.caseware.fanout.app.PublishHandler;
import com.caseware.fanout.capture.BackfillMetrics.Counter;
import com.caseware.fanout.domain.EfRef;
import com.caseware.fanout.domain.PublishEvent;
import com.caseware.fanout.domain.Region;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BackfillJobTest {

  private static final Region CA = Region.CA;

  private final CaptureRig rig = new CaptureRig();
  private final InMemoryEfCatalog catalog = new InMemoryEfCatalog();
  private final InMemoryEfLoader loader = new InMemoryEfLoader();
  private final AdjustableConcurrencyLimit limit = new AdjustableConcurrencyLimit(4);
  private final BackfillMetrics metrics = new BackfillMetrics();

  private BackfillJob job() {
    return job(new BackfillJob.Config(100, Duration.ofSeconds(1), Duration.ofMinutes(1)));
  }

  private BackfillJob job(BackfillJob.Config cfg) {
    return new BackfillJob(
        catalog, loader, rig.store, rig.flags, limit, rig.clock, new SplittableRandom(7), metrics, cfg);
  }

  private List<EfRef> seed(Region region, int n) {
    List<EfRef> efs = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      EfRef e = ef(String.format("%s-ef-%03d", region, i), 1);
      efs.add(e);
      catalog.add(region, e);
      loader.seed(region, e, new TemplateVersionRef(TPL, v("v1")));
    }
    return efs;
  }

  private static boolean await(CountDownLatch l) throws InterruptedException {
    return l.await(10, TimeUnit.SECONDS);
  }

  @Test
  @DisplayName("FR-6 TC-7: backfill loads only record-less EFs and records them as BACKFILL with a seeded baseline")
  void loadsOnlyRecordLess() {
    List<EfRef> efs = seed(EU, 6);
    rig.hooks.onCreate(EU, efs.get(0), TPL, v("v9"), t(1));
    rig.hooks.onSave(EU, efs.get(1), TPL, v("v9"), t(1));
    rig.drainAll();

    job().run(EU);

    assertThat(loader.totalLoads()).isEqualTo(4);
    assertThat(loader.loadCount(EU, efs.get(0))).isZero();
    assertThat(loader.loadCount(EU, efs.get(1))).isZero();
    var rec = rig.store.find(EU, efs.get(2)).orElseThrow();
    assertThat(rec.source()).isEqualTo(CaptureSource.BACKFILL);
    assertThat(rec.version()).isEqualTo(v("v1"));
    assertThat(metrics.get(Counter.RECORDED, EU)).isEqualTo(4);
    assertThat(metrics.get(Counter.SKIPPED_HOOK_COVERED, EU)).isEqualTo(2);
    assertThat(metrics.get(Counter.ENUMERATED, EU)).isEqualTo(6);
  }

  @Test
  @DisplayName("FR-6 TC-8: an EF covered by a hook between enumeration and load is not loaded")
  void hookCoveredBetweenEnumerationAndLoad() {
    limit.setLimit(1);
    List<EfRef> efs = seed(EU, 3);
    // while ef-000 is loading, a hook captures ef-001 (already enumerated, not yet loaded)
    loader.latencyHook(
        e -> {
          if (e.equals(efs.get(0))) {
            rig.hooks.onCreate(EU, efs.get(1), TPL, v("v7"), t(5));
            rig.drainAll();
          }
        });

    job().run(EU);

    assertThat(loader.loadCount(EU, efs.get(1))).isZero();
    assertThat(rig.store.find(EU, efs.get(1)).orElseThrow().version()).isEqualTo(v("v7"));
    assertThat(metrics.get(Counter.SKIPPED_HOOK_COVERED, EU)).isEqualTo(1);
    assertThat(metrics.get(Counter.RECORDED, EU)).isEqualTo(2);
  }

  @Test
  @DisplayName("FR-5 FR-6: a hook capture that lands during the load has a later timestamp than the load start and wins")
  void hookWinsRaceAgainstLoadStart() {
    limit.setLimit(1);
    List<EfRef> efs = seed(EU, 1);
    loader.latencyHook(
        e -> {
          rig.hooks.onOpen(EU, e, TPL, v("v2"), CaptureRig.T0.plusSeconds(5));
          rig.drainAll();
        });

    job().run(EU);

    var rec = rig.store.find(EU, efs.get(0)).orElseThrow();
    assertThat(rec.version()).isEqualTo(v("v2"));
    assertThat(rec.source()).isEqualTo(CaptureSource.OPEN);
    assertThat(metrics.get(Counter.RECORDED, EU)).isZero();
    assertThat(metrics.get(Counter.SKIPPED_HOOK_COVERED, EU)).isEqualTo(1);
  }

  @Test
  @DisplayName("FR-7 TC-9: a failed load leaves the EF record-less, schedules a retry, and a later success records it")
  void failedThenRecovered() {
    List<EfRef> efs = seed(EU, 2);
    loader.failTimes(EU, efs.get(0), 2);
    BackfillJob job = job();

    var r1 = job.step(EU);
    assertThat(rig.store.find(EU, efs.get(0))).isEmpty(); // no placeholder
    assertThat(rig.store.find(EU, efs.get(1))).isPresent();
    assertThat(r1.pendingRetries()).isEqualTo(1);
    assertThat(r1.nextRetryAt()).isPresent();
    assertThat(metrics.get(Counter.FAILED, EU)).isEqualTo(1);
    assertThat(metrics.get(Counter.RETRY_SCHEDULED, EU)).isEqualTo(1);

    rig.advance(Duration.ofMinutes(1)); // beyond the jitter ceiling
    job.step(EU);
    assertThat(rig.store.find(EU, efs.get(0))).isEmpty();
    assertThat(metrics.get(Counter.FAILED, EU)).isEqualTo(2);

    rig.advance(Duration.ofMinutes(1));
    var r3 = job.step(EU);
    assertThat(rig.store.find(EU, efs.get(0))).isPresent();
    assertThat(r3.pendingRetries()).isZero();
    assertThat(metrics.get(Counter.RECORDED, EU)).isEqualTo(2);
    assertThat(metrics.get(Counter.ATTEMPTED, EU)).isEqualTo(4);
  }

  @Test
  @DisplayName("FR-7 TC-9: a retry is not attempted before its backoff has elapsed (no sleeping, fake clock)")
  void retryWaitsForBackoff() {
    List<EfRef> efs = seed(EU, 1);
    loader.failAlways(EU, efs.get(0));
    BackfillJob job = job(new BackfillJob.Config(10, Duration.ofSeconds(100), Duration.ofSeconds(100)));
    job.step(EU);
    Instant due = job.status(EU).nextRetryAt().orElseThrow();
    assertThat(due).isBetween(rig.clock.instant(), rig.clock.instant().plusSeconds(100));

    if (due.isAfter(rig.clock.instant())) {
      job.step(EU);
      assertThat(loader.totalLoads()).isEqualTo(1);
    }
    rig.advance(Duration.ofSeconds(100));
    job.step(EU);
    assertThat(loader.totalLoads()).isEqualTo(2);
  }

  @Test
  @DisplayName("FR-7 TC-9: a permanently failing EF never gets a record, is retried with a capped backoff and counted failed")
  void permanentlyFailing() {
    List<EfRef> efs = seed(EU, 3);
    loader.failAlways(EU, efs.get(1));
    BackfillJob job = job();
    for (int i = 0; i < 8; i++) {
      job.step(EU);
      rig.advance(Duration.ofMinutes(1)); // cap is 1 minute
    }
    assertThat(rig.store.find(EU, efs.get(1))).isEmpty();
    assertThat(loader.loadCount(EU, efs.get(1))).isEqualTo(8);
    assertThat(metrics.get(Counter.FAILED, EU)).isEqualTo(8);
    assertThat(metrics.get(Counter.RECORDED, EU)).isEqualTo(2);
    assertThat(job.status(EU).pendingRetries()).isEqualTo(1);
    assertThat(metrics.coverage(EU)).isEqualTo(2.0 / 3.0);
  }

  @Test
  @DisplayName("NFR-4 TC-13: in-flight loads never exceed the limit under real threads")
  void inFlightNeverExceedsLimit() throws Exception {
    limit.setLimit(3);
    seed(EU, 12);
    CountDownLatch entered = new CountDownLatch(3);
    CountDownLatch release = new CountDownLatch(1);
    loader.latencyHook(
        e -> {
          entered.countDown();
          try {
            release.await(10, TimeUnit.SECONDS);
          } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
          }
        });
    BackfillJob job = job();

    CompletableFuture<BackfillJob.StepResult> done =
        CompletableFuture.supplyAsync(() -> job.step(EU));
    assertThat(await(entered)).isTrue();
    assertThat(loader.inFlight()).isEqualTo(3); // full, the dispatcher waits for a free slot
    release.countDown();
    done.get(10, TimeUnit.SECONDS);

    assertThat(loader.maxInFlight()).isEqualTo(3);
    assertThat(loader.totalLoads()).isEqualTo(12);
    assertThat(metrics.get(Counter.RECORDED, EU)).isEqualTo(12);
  }

  @Test
  @DisplayName("NFR-4 TC-14: the limit is lowered and raised at runtime and takes effect for the next load")
  void limitChangesAtRuntime() throws Exception {
    seed(EU, 12);
    BackfillJob job = job(new BackfillJob.Config(4, Duration.ofSeconds(1), Duration.ofMinutes(1)));
    AtomicReference<CountDownLatch> gate = new AtomicReference<>(new CountDownLatch(0));
    loader.latencyHook(
        e -> {
          CountDownLatch g = gate.get();
          g.countDown();
          try {
            g.await(10, TimeUnit.SECONDS);
          } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
          }
        });

    limit.setLimit(1);
    job.step(EU); // one page of 4
    assertThat(loader.maxInFlight()).isEqualTo(1);

    limit.setLimit(4); // raised: all four of the next page run together
    loader.resetMaxInFlight();
    gate.set(new CountDownLatch(4));
    job.step(EU);
    assertThat(loader.maxInFlight()).isEqualTo(4);

    limit.setLimit(2); // lowered
    loader.resetMaxInFlight();
    gate.set(new CountDownLatch(2));
    job.step(EU);
    assertThat(loader.maxInFlight()).isEqualTo(2);
    assertThat(loader.totalLoads()).isEqualTo(12);

    assertThatThrownBy(() -> limit.setLimit(0)).isInstanceOf(IllegalArgumentException.class);
    assertThat(limit.limit()).isEqualTo(2);
  }

  @Test
  @DisplayName("NFR-4: retries respect the same limit")
  void retriesRespectLimit() {
    limit.setLimit(2);
    List<EfRef> efs = seed(EU, 6);
    efs.forEach(e -> loader.failTimes(EU, e, 1));
    BackfillJob job = job();
    job.step(EU);
    rig.advance(Duration.ofMinutes(1));
    job.step(EU);
    assertThat(loader.maxInFlight()).isLessThanOrEqualTo(2);
    assertThat(metrics.get(Counter.RECORDED, EU)).isEqualTo(6);
  }

  @Test
  @DisplayName("NFR-5 NFR-23 TC-15: load budget, 800,000 x 1 min needs at least 40 concurrent loads for 14 days")
  void loadBudgetArithmetic() {
    double minutes = 800_000;
    double days40 = minutes / 40 / 60 / 24;
    double days50 = minutes / 50 / 60 / 24;
    double days20 = minutes / 20 / 60 / 24;
    assertThat(days40).isBetween(13.8, 13.95); // 13.9 days: no slack
    assertThat(days50).isBetween(11.05, 11.15); // 11.1 days: about 20% headroom
    assertThat(days20).isBetween(27.7, 27.9); // 28 days: NFR-5 fails
    assertThat(days40).isLessThan(14.0);
    assertThat(days20).isGreaterThan(14.0);
    assertThat(Math.ceil(minutes / 60 / (14 * 24))).isEqualTo(40.0);
  }

  @Test
  @DisplayName("NFR-38 TC-16: coverage is reported per region, EU and CA separately, with attempted/recorded/failed apart")
  void coveragePerRegion() {
    List<EfRef> eu = seed(EU, 4);
    List<EfRef> ca = seed(CA, 4);
    rig.hooks.onCreate(EU, eu.get(0), TPL, v("v3"), t(1)); // hook-covered counts as covered
    rig.drainAll();
    loader.failAlways(CA, ca.get(0));
    loader.failAlways(CA, ca.get(1));
    BackfillJob job = job();

    job.run(EU);
    job.run(CA);

    assertThat(metrics.coverage(EU)).isEqualTo(1.0);
    assertThat(metrics.coverage(CA)).isEqualTo(0.5);
    assertThat(metrics.get(Counter.ATTEMPTED, CA)).isEqualTo(4);
    assertThat(metrics.get(Counter.RECORDED, CA)).isEqualTo(2);
    assertThat(metrics.get(Counter.FAILED, CA)).isEqualTo(2);
    assertThat(metrics.get(Counter.ATTEMPTED, EU)).isEqualTo(3);
    assertThat(metrics.get(Counter.FAILED, EU)).isZero();

    var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    metrics.bind(registry);
    assertThat(registry.get("capture.backfill.coverage").tag("region", "CA").gauge().value())
        .isEqualTo(0.5);
    assertThat(
            registry
                .get("capture.backfill")
                .tag("counter", "FAILED")
                .tag("region", "CA")
                .functionCounter()
                .count())
        .isEqualTo(2.0);
  }

  @Test
  @DisplayName("NFR-41 TC-18: a flag-off firm is neither loaded nor written")
  void flagOffFirm() {
    List<EfRef> on = seed(EU, 2);
    EfRef off = ef("off-ef", 2);
    catalog.add(EU, off);
    loader.seed(EU, off, new TemplateVersionRef(TPL, v("v1")));
    rig.flags.setEnabled(firm(2), false);

    job().run(EU);

    assertThat(loader.loadCount(EU, off)).isZero();
    assertThat(rig.store.find(EU, off)).isEmpty();
    assertThat(metrics.get(Counter.SKIPPED_FLAG_OFF, EU)).isEqualTo(1);
    assertThat(loader.totalLoads()).isEqualTo(on.size());
    assertThat(job().status(EU).pendingRetries()).isZero();
  }

  @Test
  @DisplayName("NFR-42 TC-19: a flag-off EF is deferred, counted once, not loaded; after re-enable it is recorded")
  void flagOffDeferredThenProcessed() {
    seed(EU, 1);
    EfRef off = ef("off-ef", 2);
    catalog.add(EU, off);
    loader.seed(EU, off, new TemplateVersionRef(TPL, v("v1")));
    rig.flags.setEnabled(firm(2), false);
    BackfillJob job = job();

    var r1 = job.step(EU);
    assertThat(r1.deferred()).isEqualTo(1);
    job.step(EU);
    job.step(EU);
    assertThat(job.status(EU).deferred()).isEqualTo(1);
    assertThat(metrics.get(Counter.SKIPPED_FLAG_OFF, EU)).isEqualTo(1); // once, not per step
    assertThat(loader.loadCount(EU, off)).isZero();

    rig.flags.setEnabled(firm(2), true);
    job.step(EU);
    assertThat(job.status(EU).deferred()).isZero();
    assertThat(rig.store.find(EU, off)).isPresent();
    assertThat(loader.loadCount(EU, off)).isEqualTo(1);
    assertThat(metrics.get(Counter.SKIPPED_FLAG_OFF, EU)).isEqualTo(1);
  }

  @Test
  @DisplayName("FR-7: a flag lookup failure schedules a retry, nothing is dropped and the step does not abort")
  void flagReadFailureRetried() {
    List<EfRef> efs = seed(EU, 3);
    int[] fails = {1};
    var flaky =
        new com.caseware.fanout.port.FirmFlags() {
          public boolean enabled(com.caseware.fanout.domain.FirmId f) {
            if (fails[0]-- > 0) {
              throw new IllegalStateException("flag blip");
            }
            return rig.flags.enabled(f);
          }
        };
    BackfillJob job =
        new BackfillJob(
            catalog, loader, rig.store, flaky, limit, rig.clock, new SplittableRandom(7), metrics,
            new BackfillJob.Config(100, Duration.ofSeconds(1), Duration.ofMinutes(1)));
    var r = job.step(EU);
    assertThat(r.pendingRetries()).isEqualTo(1);
    assertThat(metrics.get(Counter.FAILED, EU)).isEqualTo(1);
    assertThat(metrics.get(Counter.RETRY_SCHEDULED, EU)).isEqualTo(1);
    rig.advance(Duration.ofMinutes(2));
    job.step(EU);
    for (EfRef e : efs) {
      assertThat(rig.store.find(EU, e)).as("record for %s", e).isPresent();
    }
  }

  @Test
  @DisplayName("FR-5 FR-6: the record carries the load START instant even when the clock advances during the load")
  void recordCarriesLoadStartInstant() {
    limit.setLimit(1);
    List<EfRef> efs = seed(EU, 1);
    loader.latencyHook(e -> rig.advance(Duration.ofMinutes(10)));
    job().run(EU);
    var rec = rig.store.find(EU, efs.get(0)).orElseThrow();
    assertThat(rec.versionCapturedAt()).isEqualTo(CaptureRig.T0);
    assertThat(rig.clock.instant()).isEqualTo(CaptureRig.T0.plus(Duration.ofMinutes(10)));
  }

  @Test
  @DisplayName("FR-6: an EF waiting for a retry that gets covered by a hook is skipped on its retry, with no load")
  void retriedEfCoveredWhileWaiting() {
    List<EfRef> efs = seed(EU, 1);
    loader.failTimes(EU, efs.get(0), 1);
    BackfillJob job = job();
    job.step(EU);
    assertThat(job.status(EU).pendingRetries()).isEqualTo(1);
    assertThat(loader.loadCount(EU, efs.get(0))).isEqualTo(1);

    rig.hooks.onOpen(EU, efs.get(0), TPL, v("v4"), t(30));
    rig.drainAll();
    rig.advance(Duration.ofMinutes(2));
    var r = job.step(EU);

    assertThat(loader.loadCount(EU, efs.get(0))).isEqualTo(1); // no second load
    assertThat(r.pendingRetries()).isZero();
    assertThat(metrics.get(Counter.SKIPPED_HOOK_COVERED, EU)).isEqualTo(1);
    assertThat(rig.store.find(EU, efs.get(0)).orElseThrow().version()).isEqualTo(v("v4"));
  }

  @Test
  @DisplayName("ADR 0003: pause stops new loads without losing anything and resume continues")
  void pauseAndResume() {
    seed(EU, 5);
    BackfillJob job = job(new BackfillJob.Config(2, Duration.ofSeconds(1), Duration.ofMinutes(1)));
    job.pause();
    job.run(EU);
    assertThat(loader.totalLoads()).isZero();
    job.resume();
    job.run(EU);
    assertThat(loader.totalLoads()).isEqualTo(5);
    assertThat(metrics.get(Counter.RECORDED, EU)).isEqualTo(5);
    assertThat(job.status(EU).enumerationDone()).isTrue();
  }

  @Test
  @DisplayName("NFR-22 TC-10: hooks and the publish path make 0 EfLoader calls; only backfill loads")
  void onlyBackfillLoads() {
    List<EfRef> efs = seed(EU, 6);
    rig.hooks.onCreate(EU, efs.get(0), TPL, v("v1"), t(1));
    rig.hooks.onOpen(EU, efs.get(1), TPL, v("v1"), t(1));
    rig.hooks.onSave(EU, efs.get(2), TPL, v("v1"), t(1));
    rig.drainAll();

    InMemoryWorkItemStore items = new InMemoryWorkItemStore();
    InMemoryPublishProgressStore progress = new InMemoryPublishProgressStore();
    new PublishHandler(
            EU, new RecordBackedTemplateVersionIndex(rig.store), items, progress, rig.clock, 4)
        .handle(new PublishEvent("p1", TPL, v("v5"), CaptureRig.T0));
    assertThat(progress.totals("p1").total()).isEqualTo(3);
    assertThat(loader.totalLoads()).isZero();

    job().run(EU);
    assertThat(loader.totalLoads()).isEqualTo(3); // exactly the record-less EFs
  }

  @Test
  @DisplayName("ADR 0002 c.7 FR-4: a backfilled record has baseline = version at EPOCH, so a later accept wins")
  void seedingThenAccept() {
    List<EfRef> efs = seed(EU, 1);
    job().run(EU);
    var rec = rig.store.find(EU, efs.get(0)).orElseThrow();
    assertThat(rec.baseline()).contains(v("v1"));
    assertThat(rec.baselineCapturedAt()).contains(Instant.EPOCH);

    rig.hooks.onAccept(EU, efs.get(0), TPL, v("v3"), t(10));
    rig.drainAll();
    assertThat(rig.store.find(EU, efs.get(0)).orElseThrow().baseline()).contains(v("v3"));
  }
}
