package com.caseware.fanout.capture;

import static com.caseware.fanout.capture.CaptureRig.EU;
import static com.caseware.fanout.capture.CaptureRig.OTHER;
import static com.caseware.fanout.capture.CaptureRig.TPL;
import static com.caseware.fanout.capture.CaptureRig.ef;
import static com.caseware.fanout.capture.CaptureRig.t;
import static com.caseware.fanout.capture.CaptureRig.v;
import static com.caseware.fanout.capture.UpsertResult.APPLIED;
import static com.caseware.fanout.capture.UpsertResult.NOOP_CONFLICT;
import static com.caseware.fanout.capture.UpsertResult.NOOP_DUPLICATE;
import static com.caseware.fanout.capture.UpsertResult.NOOP_NO_RECORD;
import static com.caseware.fanout.capture.UpsertResult.NOOP_STALE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.caseware.fanout.adapter.memory.InMemoryTemplateVersionRecordStore;
import com.caseware.fanout.domain.EfRef;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TemplateVersionRecordStoreTest {

  private final InMemoryTemplateVersionRecordStore store = new InMemoryTemplateVersionRecordStore();
  private final EfRef ef = ef("ef-1");

  private TemplateVersionRecord rec() {
    return store.find(EU, ef).orElseThrow();
  }

  @Test
  @DisplayName("FR-5 NFR-27 TC-5: duplicate version write is NOOP_DUPLICATE and keeps one record")
  void duplicate() {
    assertThat(store.upsertVersion(EU, ef, TPL, v("v1"), t(1), CaptureSource.SAVE)).isEqualTo(APPLIED);
    assertThat(store.upsertVersion(EU, ef, TPL, v("v1"), t(1), CaptureSource.SAVE))
        .isEqualTo(NOOP_DUPLICATE);
    assertThat(store.scan(EU, Optional.empty())).hasSize(1);
    assertThat(store.metrics().get(NOOP_DUPLICATE)).isEqualTo(1);
  }

  @Test
  @DisplayName("FR-5 TC-6: an older capture is NOOP_STALE and the newer one is kept")
  void outOfOrder() {
    store.upsertVersion(EU, ef, TPL, v("v2"), t(20), CaptureSource.SAVE);
    assertThat(store.upsertVersion(EU, ef, TPL, v("v1"), t(10), CaptureSource.OPEN))
        .isEqualTo(NOOP_STALE);
    assertThat(rec().version()).isEqualTo(v("v2"));
    assertThat(rec().versionCapturedAt()).isEqualTo(t(20));
    assertThat(store.upsertVersion(EU, ef, TPL, v("v3"), t(30), CaptureSource.SAVE)).isEqualTo(APPLIED);
    assertThat(rec().version()).isEqualTo(v("v3"));
  }

  @Test
  @DisplayName("ADR 0002 cl.2: equal timestamp with different content is NOOP_CONFLICT, kept and counted")
  void conflict() {
    store.upsertVersion(EU, ef, TPL, v("v1"), t(5), CaptureSource.SAVE);
    assertThat(store.upsertVersion(EU, ef, TPL, v("v2"), t(5), CaptureSource.SAVE))
        .isEqualTo(NOOP_CONFLICT);
    assertThat(store.upsertVersion(EU, ef, OTHER, v("v1"), t(5), CaptureSource.SAVE))
        .isEqualTo(NOOP_CONFLICT);
    assertThat(rec().version()).isEqualTo(v("v1"));
    assertThat(rec().template()).isEqualTo(TPL);
    assertThat(store.metrics().get(NOOP_CONFLICT)).isEqualTo(2);
  }

  @Test
  @DisplayName("FR-4 TC-4: accept sets the baseline only; version, versionCapturedAt and source untouched")
  void acceptWritesBaselineOnly() {
    store.upsertVersion(EU, ef, TPL, v("v1"), t(1), CaptureSource.SAVE);
    TemplateVersionRecord before = rec();
    assertThat(store.upsertBaseline(EU, ef, TPL, v("v3"), t(50))).isEqualTo(APPLIED);
    TemplateVersionRecord after = rec();
    assertThat(after.baseline()).contains(v("v3"));
    assertThat(after.baselineCapturedAt()).contains(t(50));
    assertThat(after.version()).isEqualTo(before.version());
    assertThat(after.versionCapturedAt()).isEqualTo(before.versionCapturedAt());
    assertThat(after.source()).isEqualTo(before.source());
  }

  @Test
  @DisplayName("FR-5 TC-4: duplicate accept NOOP_DUPLICATE, older accept NOOP_STALE, equal different NOOP_CONFLICT")
  void acceptConditional() {
    store.upsertVersion(EU, ef, TPL, v("v1"), t(1), CaptureSource.CREATE);
    store.upsertBaseline(EU, ef, TPL, v("v3"), t(50));
    assertThat(store.upsertBaseline(EU, ef, TPL, v("v3"), t(50))).isEqualTo(NOOP_DUPLICATE);
    assertThat(store.upsertBaseline(EU, ef, TPL, v("v2"), t(40))).isEqualTo(NOOP_STALE);
    assertThat(store.upsertBaseline(EU, ef, TPL, v("v4"), t(50))).isEqualTo(NOOP_CONFLICT);
    assertThat(rec().baseline()).contains(v("v3"));
    assertThat(store.upsertBaseline(EU, ef, TPL, v("v5"), t(60))).isEqualTo(APPLIED);
    assertThat(rec().baseline()).contains(v("v5"));
  }

  @Test
  @DisplayName("ADR 0002 cl.3: accept with no record is NOOP_NO_RECORD, counted, nothing created")
  void acceptNoRecord() {
    assertThat(store.upsertBaseline(EU, ef, TPL, v("v3"), t(50))).isEqualTo(NOOP_NO_RECORD);
    assertThat(store.find(EU, ef)).isEmpty();
    assertThat(store.metrics().get(NOOP_NO_RECORD)).isEqualTo(1);
  }

  @Test
  @DisplayName("ADR 0002: accept for another template is NOOP_CONFLICT and changes nothing")
  void acceptOtherTemplate() {
    store.upsertVersion(EU, ef, TPL, v("v1"), t(1), CaptureSource.SAVE);
    TemplateVersionRecord before = rec();
    assertThat(store.upsertBaseline(EU, ef, OTHER, v("v9"), t(50))).isEqualTo(NOOP_CONFLICT);
    assertThat(rec()).isEqualTo(before);
  }

  @Test
  @DisplayName("ADR 0002 cl.4: a creating write seeds baseline = version with EPOCH, for every source")
  void createSeeds() {
    for (CaptureSource s :
        List.of(
            CaptureSource.CREATE, CaptureSource.OPEN, CaptureSource.SAVE, CaptureSource.BACKFILL)) {
      EfRef e = ef("seed-" + s);
      store.upsertVersion(EU, e, TPL, v("v1"), t(1), s);
      TemplateVersionRecord r = store.find(EU, e).orElseThrow();
      assertThat(r.baseline()).as("%s", s).contains(v("v1"));
      assertThat(r.baselineCapturedAt()).as("%s", s).contains(Instant.EPOCH);
      assertThat(r.source()).isEqualTo(s);
    }
  }

  @Test
  @DisplayName("ADR 0002 cl.4: open/save updating an existing record never touch its baseline")
  void updatingNeverTouchesBaseline() {
    store.upsertVersion(EU, ef, TPL, v("v1"), t(1), CaptureSource.CREATE);
    store.upsertBaseline(EU, ef, TPL, v("v2"), t(10));
    store.upsertVersion(EU, ef, TPL, v("v4"), t(20), CaptureSource.OPEN);
    store.upsertVersion(EU, ef, TPL, v("v5"), t(30), CaptureSource.SAVE);
    assertThat(rec().baseline()).contains(v("v2"));
    assertThat(rec().baselineCapturedAt()).contains(t(10));
    assertThat(rec().version()).isEqualTo(v("v5"));
  }

  @Test
  @DisplayName("ADR 0002 cl.4: a real accept after a seed wins over the EPOCH seed")
  void realAcceptBeatsSeed() {
    store.upsertVersion(EU, ef, TPL, v("v1"), t(1), CaptureSource.CREATE);
    assertThat(rec().baselineCapturedAt()).contains(Instant.EPOCH);
    assertThat(store.upsertBaseline(EU, ef, TPL, v("v0"), t(1))).isEqualTo(APPLIED);
    assertThat(rec().baseline()).contains(v("v0"));
  }

  @Test
  @DisplayName("ADR 0002: template change from open clears the baseline and does not re-seed")
  void templateChangeByOpenClears() {
    store.upsertVersion(EU, ef, TPL, v("v1"), t(1), CaptureSource.CREATE);
    store.upsertBaseline(EU, ef, TPL, v("v2"), t(5));
    assertThat(store.upsertVersion(EU, ef, OTHER, v("w1"), t(10), CaptureSource.OPEN)).isEqualTo(APPLIED);
    assertThat(rec().template()).isEqualTo(OTHER);
    assertThat(rec().version()).isEqualTo(v("w1"));
    assertThat(rec().baseline()).isEmpty();
    assertThat(rec().baselineCapturedAt()).isEmpty();
  }

  @Test
  @DisplayName("ADR 0002: template change from create or backfill clears and re-seeds with EPOCH")
  void templateChangeByCreateOrBackfillReseeds() {
    store.upsertVersion(EU, ef, TPL, v("v1"), t(1), CaptureSource.CREATE);
    store.upsertBaseline(EU, ef, TPL, v("v2"), t(5));
    store.upsertVersion(EU, ef, OTHER, v("w1"), t(10), CaptureSource.CREATE);
    assertThat(rec().baseline()).contains(v("w1"));
    assertThat(rec().baselineCapturedAt()).contains(Instant.EPOCH);

    EfRef e2 = ef("ef-2");
    store.upsertVersion(EU, e2, TPL, v("v1"), t(1), CaptureSource.SAVE);
    store.upsertVersion(EU, e2, OTHER, v("w3"), t(2), CaptureSource.BACKFILL);
    assertThat(store.find(EU, e2).orElseThrow().baseline()).contains(v("w3"));
  }

  @Test
  @DisplayName("ADR 0002 cl.4: backfill/create seeds an ABSENT baseline of a same-template record")
  void backfillSeedsAbsentBaseline() {
    store.upsertVersion(EU, ef, TPL, v("v1"), t(1), CaptureSource.CREATE);
    store.upsertVersion(EU, ef, OTHER, v("w1"), t(2), CaptureSource.OPEN); // cleared, no seed
    assertThat(rec().baseline()).isEmpty();
    store.upsertVersion(EU, ef, OTHER, v("w2"), t(3), CaptureSource.SAVE);
    assertThat(rec().baseline()).isEmpty(); // save never seeds an existing record
    store.upsertVersion(EU, ef, OTHER, v("w3"), t(4), CaptureSource.BACKFILL);
    assertThat(rec().baseline()).contains(v("w3"));
    assertThat(rec().baselineCapturedAt()).contains(Instant.EPOCH);
  }

  @Test
  @DisplayName("ADR 0002: stale, duplicate and conflicting writes seed nothing")
  void noOpsSeedNothing() {
    store.upsertVersion(EU, ef, TPL, v("v1"), t(10), CaptureSource.CREATE);
    store.upsertVersion(EU, ef, OTHER, v("w1"), t(20), CaptureSource.OPEN); // baseline cleared
    assertThat(store.upsertVersion(EU, ef, OTHER, v("w0"), t(5), CaptureSource.CREATE)).isEqualTo(NOOP_STALE);
    assertThat(store.upsertVersion(EU, ef, OTHER, v("w1"), t(20), CaptureSource.BACKFILL))
        .isEqualTo(NOOP_DUPLICATE);
    assertThat(store.upsertVersion(EU, ef, OTHER, v("w9"), t(20), CaptureSource.CREATE))
        .isEqualTo(NOOP_CONFLICT);
    assertThat(rec().baseline()).isEmpty();
    assertThat(rec().baselineCapturedAt()).isEmpty();
  }

  @Test
  @DisplayName("ADR 0002: ACCEPT is never a version source and never stored")
  void acceptIsNotAVersionSource() {
    assertThatThrownBy(() -> store.upsertVersion(EU, ef, TPL, v("v1"), t(1), CaptureSource.ACCEPT))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(store.find(EU, ef)).isEmpty();
  }

  @Test
  @DisplayName("NFR-35: records are per region; the same EF id in another region is separate")
  void regionsAreSeparate() {
    store.upsertVersion(EU, ef, TPL, v("v1"), t(1), CaptureSource.SAVE);
    assertThat(store.find(com.caseware.fanout.domain.Region.CA, ef)).isEmpty();
  }

  @Test
  @DisplayName("NFR-27: many threads on one EF in any order give the same deterministic final state")
  void concurrentSameEf() throws Exception {
    int n = 64;
    List<Integer> order = new ArrayList<>();
    for (int i = 1; i <= n; i++) {
      order.add(i);
    }
    Collections.shuffle(order, new java.util.Random(42));
    ExecutorService pool = Executors.newFixedThreadPool(16);
    CountDownLatch go = new CountDownLatch(1);
    List<Future<?>> fs = new ArrayList<>();
    for (int i : order) {
      fs.add(
          pool.submit(
              () -> {
                go.await();
                // distinct timestamps; every 4th message is an accept, every 3rd is delivered twice
                if (i % 4 == 0) {
                  store.upsertBaseline(EU, ef, TPL, v("a" + i), t(1000 + i));
                } else {
                  store.upsertVersion(EU, ef, TPL, v("v" + i), t(i), CaptureSource.SAVE);
                  if (i % 3 == 0) {
                    store.upsertVersion(EU, ef, TPL, v("v" + i), t(i), CaptureSource.SAVE);
                  }
                }
                return null;
              }));
    }
    store.upsertVersion(EU, ef, TPL, v("v0"), t(0), CaptureSource.CREATE); // record exists first
    go.countDown();
    for (Future<?> f : fs) {
      f.get();
    }
    pool.shutdown();
    TemplateVersionRecord r = rec();
    // newest non-accept is i=63 (64 is an accept); newest accept is i=64
    assertThat(r.version()).isEqualTo(v("v63"));
    assertThat(r.versionCapturedAt()).isEqualTo(t(63));
    assertThat(r.baseline()).contains(v("a64"));
    assertThat(r.baselineCapturedAt()).contains(t(1064));
    assertThat(store.scan(EU, Optional.empty())).hasSize(1);
  }
}
