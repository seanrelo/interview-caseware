package com.caseware.fanout.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.fanout.adapter.memory.InMemoryPublishProgressStore;
import com.caseware.fanout.adapter.memory.InMemoryTemplateVersionIndex;
import com.caseware.fanout.adapter.memory.InMemoryWorkItemStore;
import com.caseware.fanout.domain.EfId;
import com.caseware.fanout.domain.EfRef;
import com.caseware.fanout.domain.FirmId;
import com.caseware.fanout.domain.ItemState;
import com.caseware.fanout.domain.PublishEvent;
import com.caseware.fanout.domain.Region;
import com.caseware.fanout.domain.TemplateId;
import com.caseware.fanout.domain.VersionId;
import com.caseware.fanout.domain.WorkItem;
import com.caseware.fanout.domain.WorkKey;
import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PublishHandlerTest {

  private static final Instant T0 = Instant.parse("2026-10-30T10:00:00Z");
  private static final TemplateId TPL = new TemplateId("audit-ca");
  private static final VersionId V7 = new VersionId("v7");

  private final Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
  private final InMemoryTemplateVersionIndex index = new InMemoryTemplateVersionIndex();
  private final InMemoryWorkItemStore store = new InMemoryWorkItemStore();
  private final InMemoryPublishProgressStore progress = new InMemoryPublishProgressStore();

  private PublishHandler handler(Region region, int pageSize) {
    return new PublishHandler(region, index, store, progress, clock, pageSize);
  }

  private static PublishEvent event(String publishId) {
    return new PublishEvent(publishId, TPL, V7, T0);
  }

  private void efs(Region region, TemplateId tpl, int n) {
    for (int i = 0; i < n; i++) {
      index.register(
          region, tpl, new EfRef(new EfId(region + "-ef-" + String.format("%03d", i)), new FirmId("f" + i % 3)));
    }
  }

  private WorkKey key(Region region, int i) {
    return new WorkKey(new EfId(region + "-ef-" + String.format("%03d", i)), TPL, V7);
  }

  @Test
  @DisplayName("FR-14: pages through all matching EFs and creates one PENDING item each")
  void pagesThroughAllEfs() {
    efs(Region.EU, TPL, 25);

    handler(Region.EU, 10).handle(event("p1"));

    for (int i = 0; i < 25; i++) {
      assertThat(store.find(key(Region.EU, i)))
          .hasValueSatisfying(it -> assertThat(it.state()).isEqualTo(ItemState.PENDING));
    }
    assertThat(progress.totals("p1").total()).isEqualTo(25);
    assertThat(progress.totals("p1").sealed()).isTrue();
  }

  @Test
  @DisplayName("FR-14: page size is respected and the token chain terminates")
  void indexPagesAreBounded() {
    efs(Region.EU, TPL, 25);
    var p1 = index.findEfsByTemplate(Region.EU, TPL, Optional.empty(), 10);
    var p2 = index.findEfsByTemplate(Region.EU, TPL, p1.nextToken(), 10);
    var p3 = index.findEfsByTemplate(Region.EU, TPL, p2.nextToken(), 10);

    assertThat(p1.items()).hasSize(10);
    assertThat(p2.items()).hasSize(10);
    assertThat(p3.items()).hasSize(5);
    assertThat(p3.nextToken()).isEmpty();
  }

  @Test
  @DisplayName("FR-14: exact multiple of the page size yields no extra work")
  void exactPageMultiple() {
    efs(Region.EU, TPL, 20);

    handler(Region.EU, 10).handle(event("p1"));

    assertThat(progress.totals("p1").total()).isEqualTo(20);
  }

  @Test
  @DisplayName("FR-14: no matching EFs creates nothing and still seals the publish")
  void emptyIndex() {
    handler(Region.EU, 10).handle(event("p1"));

    assertThat(store.firmsWithQueuedWork(T0)).isEmpty();
    assertThat(progress.totals("p1").total()).isZero();
    assertThat(progress.totals("p1").sealed()).isTrue();
  }

  @Test
  @DisplayName("FR-14: EFs of another template are excluded")
  void otherTemplateExcluded() {
    efs(Region.EU, TPL, 3);
    TemplateId other = new TemplateId("tax-eu");
    index.register(Region.EU, other, new EfRef(new EfId("other-ef"), new FirmId("f9")));

    handler(Region.EU, 10).handle(event("p1"));

    assertThat(progress.totals("p1").total()).isEqualTo(3);
    assertThat(store.find(new WorkKey(new EfId("other-ef"), TPL, V7))).isEmpty();
    assertThat(store.find(new WorkKey(new EfId("other-ef"), other, V7))).isEmpty();
  }

  @Test
  @DisplayName("FR-15: a second publish for the same version creates 0 new items")
  void duplicatePublishCreatesNoNewItems() {
    efs(Region.EU, TPL, 12);
    PublishHandler h = handler(Region.EU, 5);

    h.handle(event("p1"));
    h.handle(event("p2"));

    // The second publish id owns nothing new: totals reflect only items it created or carries.
    assertThat(progress.totals("p1").total()).isEqualTo(12);
    assertThat(progress.totals("p2").total()).isZero();
    assertThat(store.leaseReady(T0, Duration.ofMinutes(1), 100, Set.of())).hasSize(12);
  }

  @Test
  @DisplayName("FR-15: items are keyed by (EF, template, target version)")
  void differentVersionIsDifferentItem() {
    efs(Region.EU, TPL, 2);
    PublishHandler h = handler(Region.EU, 5);

    h.handle(event("p1"));
    h.handle(new PublishEvent("p2", TPL, new VersionId("v8"), T0));

    assertThat(progress.totals("p2").total()).isEqualTo(2);
    assertThat(store.leaseReady(T0, Duration.ofMinutes(1), 100, Set.of())).hasSize(4);
  }

  @Test
  @DisplayName("NFR-27: a redelivered event after sealing is a no-op")
  void redeliveryAfterSealIsNoOp() {
    efs(Region.EU, TPL, 7);
    PublishHandler h = handler(Region.EU, 5);
    h.handle(event("p1"));
    var before = progress.totals("p1");

    // New EF appears between deliveries; a sealed publish must not pick it up or recount.
    index.register(Region.EU, TPL, new EfRef(new EfId("late-ef"), new FirmId("f1")));
    h.handle(event("p1"));

    assertThat(progress.totals("p1")).isEqualTo(before);
    assertThat(store.find(new WorkKey(new EfId("late-ef"), TPL, V7))).isEmpty();
  }

  @Test
  @DisplayName("NFR-27: re-running an unsealed publish counts items it already created")
  void retryOfUnsealedPublishCountsExistingItems() {
    efs(Region.EU, TPL, 4);
    // Simulate a crash after two items were created but before add/seal.
    for (int i = 0; i < 2; i++) {
      store.createIfAbsent(WorkItem.pending(key(Region.EU, i), new FirmId("f" + i), "p1", T0));
    }

    handler(Region.EU, 3).handle(event("p1"));

    assertThat(progress.totals("p1").total()).isEqualTo(4);
    assertThat(progress.totals("p1").sealed()).isTrue();
  }

  @Test
  @DisplayName("NFR-27: concurrent deliveries of one event count each item once")
  void concurrentDeliveries() throws Exception {
    efs(Region.EU, TPL, 50);
    PublishHandler h = handler(Region.EU, 7);
    AtomicInteger errors = new AtomicInteger();
    Thread[] threads = new Thread[8];
    for (int i = 0; i < threads.length; i++) {
      threads[i] =
          new Thread(
              () -> {
                try {
                  h.handle(event("p1"));
                } catch (RuntimeException e) {
                  errors.incrementAndGet();
                }
              });
      threads[i].start();
    }
    for (Thread t : threads) {
      t.join();
    }

    assertThat(errors).hasValue(0);
    assertThat(progress.totals("p1").total()).isEqualTo(50);
  }

  @Test
  @DisplayName("NFR-35: a regional worker only sees and creates items for its own region")
  void regionIsolation() {
    efs(Region.EU, TPL, 3);
    efs(Region.CA, TPL, 5);
    InMemoryWorkItemStore caStore = new InMemoryWorkItemStore();
    InMemoryPublishProgressStore caProgress = new InMemoryPublishProgressStore();

    handler(Region.EU, 10).handle(event("p1"));
    new PublishHandler(Region.CA, index, caStore, caProgress, clock, 10).handle(event("p1"));

    assertThat(progress.totals("p1").total()).isEqualTo(3);
    assertThat(caProgress.totals("p1").total()).isEqualTo(5);
    assertThat(store.find(key(Region.CA, 0))).isEmpty();
    assertThat(caStore.find(key(Region.EU, 0))).isEmpty();
    assertThat(index.findEfsByTemplate(Region.EU, TPL, Optional.empty(), 100).items())
        .allSatisfy(r -> assertThat(r.ef().value()).startsWith("EU-"));
  }

  @Test
  @DisplayName("NFR-36: PublishEvent has no EF field")
  void eventHasNoEfField() {
    assertThat(Arrays.stream(PublishEvent.class.getRecordComponents()))
        .map(RecordComponent::getType)
        .doesNotContain(EfId.class, EfRef.class, FirmId.class, WorkKey.class);
    assertThat(Arrays.stream(PublishEvent.class.getRecordComponents()))
        .map(RecordComponent::getName)
        .noneMatch(n -> n.toLowerCase().contains("ef") && !n.equals("publishedAt") || n.toLowerCase().contains("firm"));
    assertThat(Arrays.stream(PublishEvent.class.getRecordComponents()).map(RecordComponent::getName))
        .containsExactly("publishId", "templateId", "version", "publishedAt");
  }
}
