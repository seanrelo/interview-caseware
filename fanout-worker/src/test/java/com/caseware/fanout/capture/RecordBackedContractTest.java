package com.caseware.fanout.capture;

import static com.caseware.fanout.capture.CaptureRig.t;
import static com.caseware.fanout.capture.CaptureRig.v;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.caseware.fanout.adapter.memory.InMemoryBaselineLookup;
import com.caseware.fanout.adapter.memory.InMemoryTemplateVersionIndex;
import com.caseware.fanout.adapter.memory.InMemoryTemplateVersionRecordStore;
import com.caseware.fanout.adapter.memory.RecordBackedBaselineLookup;
import com.caseware.fanout.adapter.memory.RecordBackedTemplateVersionIndex;
import com.caseware.fanout.domain.EfId;
import com.caseware.fanout.domain.EfRef;
import com.caseware.fanout.domain.FirmId;
import com.caseware.fanout.domain.Page;
import com.caseware.fanout.domain.Region;
import com.caseware.fanout.domain.TemplateId;
import com.caseware.fanout.port.TemplateVersionIndex;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The record-backed adapters must behave like the old stubs for the same cases. */
class RecordBackedContractTest {

  private static final TemplateId A = new TemplateId("A");
  private static final TemplateId B = new TemplateId("B");

  private final InMemoryTemplateVersionIndex stub = new InMemoryTemplateVersionIndex();
  private final InMemoryTemplateVersionRecordStore store = new InMemoryTemplateVersionRecordStore();
  private final RecordBackedTemplateVersionIndex real = new RecordBackedTemplateVersionIndex(store);

  private static EfRef ef(int i) {
    return new EfRef(new EfId(String.format("ef-%03d", i)), new FirmId("f" + i % 3));
  }

  private void both(Region region, TemplateId tpl, int i) {
    stub.register(region, tpl, ef(i));
    store.upsertVersion(region, ef(i), tpl, v("v1"), t(i), CaptureSource.CREATE);
  }

  private static List<List<EfRef>> pages(TemplateVersionIndex ix, Region r, TemplateId t, int size) {
    List<List<EfRef>> out = new ArrayList<>();
    Optional<String> token = Optional.empty();
    do {
      Page<EfRef> p = ix.findEfsByTemplate(r, t, token, size);
      out.add(p.items());
      token = p.nextToken();
    } while (token.isPresent());
    return out;
  }

  @Test
  @DisplayName("FR-14 NFR-35: same pages, order, tokens and region filter as the stub for every page size")
  void indexMatchesStub() {
    for (int i : new int[] {7, 3, 11, 1, 9, 5, 2, 10, 4, 8, 6, 12}) { // insertion order scrambled
      both(Region.EU, i % 2 == 0 ? A : B, i);
    }
    both(Region.CA, A, 20);
    both(Region.CA, A, 1); // same EF id in the other region
    for (int size : new int[] {1, 2, 3, 6, 100}) {
      for (Region r : Region.values()) {
        for (TemplateId tpl : List.of(A, B, new TemplateId("none"))) {
          assertThat(pages(real, r, tpl, size)).as("%s %s size %d", r, tpl, size).isEqualTo(pages(stub, r, tpl, size));
        }
      }
    }
    // token semantics: token = last id returned, only present when more remain
    Page<EfRef> first = real.findEfsByTemplate(Region.EU, A, Optional.empty(), 3);
    assertThat(first.nextToken()).contains(first.items().get(2).ef().value());
    assertThat(real.findEfsByTemplate(Region.EU, A, Optional.empty(), 6).nextToken()).isEmpty();
    assertThat(stub.findEfsByTemplate(Region.EU, A, Optional.empty(), 6).nextToken()).isEmpty();
  }

  @Test
  @DisplayName("FR-14: pageSize must be > 0, like the stub")
  void pageSizeValidated() {
    assertThatThrownBy(() -> real.findEfsByTemplate(Region.EU, A, Optional.empty(), 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> stub.findEfsByTemplate(Region.EU, A, Optional.empty(), 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("FR-14: buckets by the CURRENT template id; an EF with no record is absent")
  void bucketsByCurrentTemplate() {
    both(Region.EU, A, 1);
    both(Region.EU, A, 2);
    store.upsertVersion(Region.EU, ef(1), B, v("w1"), t(100), CaptureSource.OPEN); // moved to B
    assertThat(pages(real, Region.EU, A, 10).get(0)).containsExactly(ef(2));
    assertThat(pages(real, Region.EU, B, 10).get(0)).containsExactly(ef(1));
    assertThat(pages(real, Region.EU, A, 10).get(0)).doesNotContain(ef(3)); // never captured
  }

  @Test
  @DisplayName("FR-4: baseline lookup returns the baseline field only, like the stub where both can answer")
  void baselineMatchesStub() {
    InMemoryBaselineLookup stubLookup = new InMemoryBaselineLookup();
    RecordBackedBaselineLookup real = new RecordBackedBaselineLookup(store);
    Region r = Region.EU;

    // seeded baseline (create) and a later accept
    store.upsertVersion(r, ef(1), A, v("v1"), t(1), CaptureSource.CREATE);
    stubLookup.set(r, ef(1), A, v("v1"));
    assertThat(real.baselineOf(r, ef(1), A)).isEqualTo(stubLookup.baselineOf(r, ef(1), A)).contains(v("v1"));
    store.upsertVersion(r, ef(1), A, v("v4"), t(2), CaptureSource.SAVE); // version moves, baseline does not
    assertThat(real.baselineOf(r, ef(1), A)).contains(v("v1"));
    store.upsertBaseline(r, ef(1), A, v("v3"), t(3));
    stubLookup.set(r, ef(1), A, v("v3"));
    assertThat(real.baselineOf(r, ef(1), A)).isEqualTo(stubLookup.baselineOf(r, ef(1), A)).contains(v("v3"));

    // no record
    assertThat(real.baselineOf(r, ef(2), A)).isEqualTo(stubLookup.baselineOf(r, ef(2), A)).isEmpty();
    // requested template differs from the record's
    assertThat(real.baselineOf(r, ef(1), B)).isEqualTo(stubLookup.baselineOf(r, ef(1), B)).isEmpty();
    // other region
    assertThat(real.baselineOf(Region.CA, ef(1), A)).isEmpty();
    // no baseline after a template change by open
    store.upsertVersion(r, ef(1), B, v("w1"), t(9), CaptureSource.OPEN);
    assertThat(real.baselineOf(r, ef(1), B)).isEmpty();
    assertThat(real.baselineOf(r, ef(1), A)).isEmpty();
  }
}
