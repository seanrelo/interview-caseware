package com.caseware.fanout.capture;

import static com.caseware.fanout.capture.CaptureRig.EU;
import static com.caseware.fanout.capture.CaptureRig.TPL;
import static com.caseware.fanout.capture.CaptureRig.ef;
import static com.caseware.fanout.capture.CaptureRig.firm;
import static com.caseware.fanout.capture.CaptureRig.t;
import static com.caseware.fanout.capture.CaptureRig.v;
import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.fanout.adapter.memory.InMemoryEfCatalog;
import com.caseware.fanout.adapter.memory.InMemoryEfLoader;
import com.caseware.fanout.adapter.memory.InMemoryTemplateVersionRecordStore;
import com.caseware.fanout.domain.EfRef;
import com.caseware.fanout.domain.Region;
import com.caseware.fanout.domain.TemplateId;
import com.caseware.fanout.domain.VersionId;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Regression tests for QA defects D1 (FR-4), D2 (FR-6/FR-7), D3 (NFR-42). */
class CaptureRegressionTest {

  @Test
  @DisplayName("FR-4: a transient failure of the version capture must not make the later accept vanish")
  void acceptSurvivesTransientVersionFailure() {
    AtomicInteger failsLeft = new AtomicInteger(1);
    CaptureRig rig =
        new CaptureRig(
            s ->
                new TemplateVersionRecordStore() {
                  public UpsertResult upsertVersion(
                      Region r, EfRef e, TemplateId t, VersionId v, Instant at, CaptureSource src) {
                    if (failsLeft.getAndDecrement() > 0) {
                      throw new IllegalStateException("blip");
                    }
                    return s.upsertVersion(r, e, t, v, at, src);
                  }

                  public UpsertResult upsertBaseline(
                      Region r, EfRef e, TemplateId t, VersionId v, Instant at) {
                    return s.upsertBaseline(r, e, t, v, at);
                  }

                  public Optional<TemplateVersionRecord> find(Region r, EfRef e) {
                    return s.find(r, e);
                  }
                },
            CaptureRelay.Config.defaults(),
            5,
            o -> o);
    EfRef e = ef("ef-1");
    rig.hooks.onCreate(EU, e, TPL, v("v1"), t(1));
    rig.hooks.onAccept(EU, e, TPL, v("v5"), t(2));
    rig.relay.drainOnce(); // create nacked, accept applied before the record exists -> NOOP_NO_RECORD, nacked and retried
    rig.advance(Duration.ofMinutes(5));
    rig.drainAll();
    assertThat(rig.store.find(EU, e)).isPresent();
    assertThat(rig.store.find(EU, e).orElseThrow().baseline()).contains(v("v5"));
  }

  @Test
  @DisplayName("FR-6/FR-7: a store.find failure in the backfill step must not silently drop the EFs of that step")
  void backfillSurvivesStoreReadFailure() {
    CaptureRig rig = new CaptureRig();
    AtomicInteger failsLeft = new AtomicInteger(1);
    InMemoryTemplateVersionRecordStore flaky =
        new InMemoryTemplateVersionRecordStore() {
          @Override
          public Optional<TemplateVersionRecord> find(Region r, EfRef e) {
            if (failsLeft.getAndDecrement() > 0) {
              throw new IllegalStateException("store read blip");
            }
            return super.find(r, e);
          }
        };
    InMemoryEfCatalog catalog = new InMemoryEfCatalog();
    InMemoryEfLoader loader = new InMemoryEfLoader();
    List<EfRef> efs = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      EfRef e = ef("ef-" + i);
      efs.add(e);
      catalog.add(EU, e);
      loader.seed(EU, e, new TemplateVersionRef(TPL, v("v1")));
    }
    BackfillJob job =
        new BackfillJob(
            catalog, loader, flaky, rig.flags, new AdjustableConcurrencyLimit(2), rig.clock,
            new SplittableRandom(1), new BackfillMetrics(),
            new BackfillJob.Config(100, Duration.ofSeconds(1), Duration.ofMinutes(1)));
    try {
      job.run(EU);
    } catch (RuntimeException expected) {
      // the step may throw; what matters is that nothing is lost afterwards
    }
    for (int i = 0; i < 5; i++) {
      rig.advance(Duration.ofMinutes(2));
      job.run(EU);
    }
    for (EfRef e : efs) {
      assertThat(flaky.find(EU, e)).as("record for %s", e).isPresent();
    }
  }

  @Test
  @DisplayName("NFR-42 TC-19: after a flag-off firm is re-enabled the running backfill resumes for its EFs")
  void backfillResumesAfterReEnable() {
    CaptureRig rig = new CaptureRig();
    InMemoryEfCatalog catalog = new InMemoryEfCatalog();
    InMemoryEfLoader loader = new InMemoryEfLoader();
    EfRef off = ef("off-ef", 2);
    catalog.add(EU, off);
    loader.seed(EU, off, new TemplateVersionRef(TPL, v("v1")));
    rig.flags.setEnabled(firm(2), false);
    BackfillJob job =
        new BackfillJob(
            catalog, loader, rig.store, rig.flags, new AdjustableConcurrencyLimit(2), rig.clock,
            new SplittableRandom(1), new BackfillMetrics(),
            new BackfillJob.Config(100, Duration.ofSeconds(1), Duration.ofMinutes(1)));
    job.run(EU);
    assertThat(rig.store.find(EU, off)).isEmpty();
    rig.flags.setEnabled(firm(2), true);
    rig.advance(Duration.ofMinutes(5));
    job.run(EU);
    assertThat(rig.store.find(EU, off)).isPresent();
  }
}
