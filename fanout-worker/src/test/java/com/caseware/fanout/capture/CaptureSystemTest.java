package com.caseware.fanout.capture;

import static com.caseware.fanout.capture.CaptureRig.EU;
import static com.caseware.fanout.capture.CaptureRig.OTHER;
import static com.caseware.fanout.capture.CaptureRig.TPL;
import static com.caseware.fanout.capture.CaptureRig.ef;
import static com.caseware.fanout.capture.CaptureRig.firm;
import static com.caseware.fanout.capture.CaptureRig.t;
import static com.caseware.fanout.capture.CaptureRig.v;
import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.fanout.adapter.memory.InMemoryPublishProgressStore;
import com.caseware.fanout.adapter.memory.InMemoryWorkItemStore;
import com.caseware.fanout.adapter.memory.RecordBackedTemplateVersionIndex;
import com.caseware.fanout.app.PublishHandler;
import com.caseware.fanout.domain.EfId;
import com.caseware.fanout.domain.PublishEvent;
import com.caseware.fanout.domain.WorkKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CaptureSystemTest {

  @Test
  @DisplayName("FR-1..3 FR-14 NFR-22 NFR-41: hooks+relay write records; PublishHandler fans out via the record index with 0 loads")
  void hooksThenPublish() {
    CaptureRig rig = new CaptureRig();
    rig.flags.setEnabled(firm(3), false); // flag-off firm: never captured, so never fanned out
    for (int i = 0; i < 30; i++) {
      var e = ef(String.format("ef-%03d", i), i % 4);
      rig.hooks.onCreate(EU, e, i % 3 == 0 ? OTHER : TPL, v("v1"), t(i));
    }
    // an EF later moves from OTHER to TPL; a duplicate and a stale save are in the stream
    rig.hooks.onOpen(EU, ef("ef-000", 0), TPL, v("v2"), t(100));
    rig.hooks.onSave(EU, ef("ef-001", 1), TPL, v("v1"), t(1));
    rig.hooks.onSave(EU, ef("ef-001", 1), TPL, v("v0"), t(0));
    rig.drainAll();

    InMemoryWorkItemStore items = new InMemoryWorkItemStore();
    InMemoryPublishProgressStore progress = new InMemoryPublishProgressStore();
    PublishHandler handler =
        new PublishHandler(
            EU, new RecordBackedTemplateVersionIndex(rig.store), items, progress, rig.clock, 4);
    PublishEvent event = new PublishEvent("p1", TPL, v("v5"), CaptureRig.T0);
    handler.handle(event);
    handler.handle(event); // redelivery is a no-op

    // expected: TPL-captured EFs of firms 0..2 (flag-off firm 3 never captured)
    long expected = 0;
    for (int i = 0; i < 30; i++) {
      boolean tpl = i % 3 != 0 || i == 0;
      if (tpl && i % 4 != 3) {
        expected++;
      }
    }
    assertThat(progress.totals("p1").total()).isEqualTo(expected);
    assertThat(progress.totals("p1").sealed()).isTrue();
    assertThat(items.find(new WorkKey(new EfId("ef-000"), TPL, v("v5")))).isPresent(); // moved EF
    assertThat(items.find(new WorkKey(new EfId("ef-003"), TPL, v("v5")))).isEmpty(); // still OTHER
    assertThat(items.find(new WorkKey(new EfId("ef-007"), TPL, v("v5")))).isEmpty(); // flag-off firm 3
    // NFR-22: the publish path cannot load an EF; no constructor of it accepts a loader or catalog
    for (Class<?> c : java.util.List.of(PublishHandler.class, RecordBackedTemplateVersionIndex.class)) {
      for (var ctor : c.getDeclaredConstructors()) {
        for (Class<?> p : ctor.getParameterTypes()) {
          assertThat(EfLoader.class.isAssignableFrom(p) || EfCatalog.class.isAssignableFrom(p))
              .as("%s ctor param %s", c.getSimpleName(), p)
              .isFalse();
        }
      }
    }
  }
}
