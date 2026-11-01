package com.caseware.fanout.status;

import static com.caseware.fanout.graph.GraphFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.fanout.adapter.memory.GraphBackedVersionGraphReader;
import com.caseware.fanout.adapter.memory.InMemoryDecisionReader;
import com.caseware.fanout.adapter.memory.InMemoryEfFirmLookup;
import com.caseware.fanout.adapter.memory.InMemoryFirmFlags;
import com.caseware.fanout.adapter.memory.InMemoryPendingUpdateMarker;
import com.caseware.fanout.adapter.memory.InMemoryStatusStore;
import com.caseware.fanout.adapter.memory.InMemoryTemplateVersionRecordStore;
import com.caseware.fanout.adapter.memory.InMemoryVersionRegistry;
import com.caseware.fanout.app.MutableClock;
import com.caseware.fanout.capture.CaptureSource;
import com.caseware.fanout.domain.EfId;
import com.caseware.fanout.domain.EfRef;
import com.caseware.fanout.domain.FirmId;
import com.caseware.fanout.domain.Region;
import com.caseware.fanout.domain.WorkKey;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T2-fix: ADR 0006 clarification 2, ADR 0008 item 1. */
class MarkUnknownSupersededTest {

  private static final Instant T0 = Instant.parse("2026-10-30T10:00:00Z");
  private static final FirmId F = new FirmId("F");
  private static final EfRef EF1 = new EfRef(new EfId("ef1"), F);

  private final InMemoryVersionRegistry reg = new InMemoryVersionRegistry();
  private final InMemoryTemplateVersionRecordStore records = new InMemoryTemplateVersionRecordStore();
  private final InMemoryStatusStore store = new InMemoryStatusStore();
  private final InMemoryEfFirmLookup lookup = new InMemoryEfFirmLookup();
  private final InMemoryPendingUpdateMarker marker =
      new InMemoryPendingUpdateMarker(new GraphBackedVersionGraphReader(reg));
  private final StatusService service =
      new StatusService(records, records, reg::snapshot, new InMemoryDecisionReader(), store,
          new InMemoryFirmFlags(), lookup, new MutableClock(T0));

  MarkUnknownSupersededTest() {
    reg.register(pub("v1", 1));
    reg.register(pub("v5", 5, "v1"));
    reg.register(pub("v7", 7, "v5"));
    lookup.add(EF1);
    records.upsertVersion(Region.EU, EF1, T, v("v1"), T0, CaptureSource.CREATE);
  }

  @Test
  @DisplayName("FR-19 ADR 0006 c2: dead letter of v5 keeps a pending v7 row")
  void supersededDeadLetterKeepsNewerPending() {
    StatusRefreshingMarker m = new StatusRefreshingMarker(marker, service, Region.EU);
    m.record(new WorkKey(EF1.ef(), T, v("v7")));
    m.markUnknown(new WorkKey(EF1.ef(), T, v("v5")), "dead-lettered");
    StatusRow row = store.find(F, EF1.ef()).orElseThrow();
    assertThat(row.status()).isEqualTo(UpdateStatus.UPDATE_PENDING);
    assertThat(row.pendingTarget()).contains(v("v7"));
    assertThat(row.source()).isEqualTo(StatusSource.DERIVED);
    assertThat(store.counts(F)).isEqualTo(new StatusCounts(0, 1, 0));
  }

  @Test
  @DisplayName("FR-19: dead letter of the current target still writes UNKNOWN from FANOUT_UNKNOWN")
  void currentTargetStillUnknown() {
    StatusRefreshingMarker m = new StatusRefreshingMarker(marker, service, Region.EU);
    m.record(new WorkKey(EF1.ef(), T, v("v7")));
    m.markUnknown(new WorkKey(EF1.ef(), T, v("v7")), "dead-lettered");
    StatusRow row = store.find(F, EF1.ef()).orElseThrow();
    assertThat(row.status()).isEqualTo(UpdateStatus.UNKNOWN);
    assertThat(row.source()).isEqualTo(StatusSource.FANOUT_UNKNOWN);
  }
}
