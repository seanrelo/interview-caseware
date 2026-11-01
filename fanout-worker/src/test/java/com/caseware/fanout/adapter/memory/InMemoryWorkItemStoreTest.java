package com.caseware.fanout.adapter.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.caseware.fanout.domain.EfId;
import com.caseware.fanout.domain.FirmId;
import com.caseware.fanout.domain.ItemState;
import com.caseware.fanout.domain.SkipReason;
import com.caseware.fanout.domain.TemplateId;
import com.caseware.fanout.domain.VersionId;
import com.caseware.fanout.domain.WorkItem;
import com.caseware.fanout.domain.WorkKey;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InMemoryWorkItemStoreTest {

  private static final Instant T0 = Instant.parse("2026-10-30T10:00:00Z");
  private static final Duration LEASE = Duration.ofSeconds(30);

  private final InMemoryWorkItemStore store = new InMemoryWorkItemStore();

  private WorkKey add(String ef, String firm, Instant notBefore) {
    WorkKey k = new WorkKey(new EfId(ef), new TemplateId("t"), new VersionId("v1"));
    store.createIfAbsent(WorkItem.pending(k, new FirmId(firm), "p", notBefore));
    return k;
  }

  @Test
  @DisplayName("NFR-10: leasing returns LEASED items with unique tokens and expiry")
  void leaseAssignsTokenAndExpiry() {
    add("a", "f", T0);
    add("b", "f", T0);

    List<WorkItem> leased = store.leaseReady(T0, LEASE, 10, Set.of());

    assertThat(leased).hasSize(2).allSatisfy(i -> {
      assertThat(i.state()).isEqualTo(ItemState.LEASED);
      assertThat(i.leaseUntil()).isEqualTo(T0.plus(LEASE));
    });
    assertThat(leased.stream().map(WorkItem::leaseToken).distinct()).hasSize(2);
    assertThat(store.leaseReady(T0, LEASE, 10, Set.of())).isEmpty();
  }

  @Test
  @DisplayName("NFR-10: lease honours max, notBefore and excluded firms")
  void leaseFilters() {
    add("a", "f1", T0);
    add("b", "f2", T0);
    add("c", "f1", T0.plusSeconds(60));

    assertThat(store.leaseReady(T0, LEASE, 0, Set.of())).isEmpty();
    assertThat(store.leaseReady(T0, LEASE, 5, Set.of(new FirmId("f1"))))
        .extracting(i -> i.key().ef().value())
        .containsExactly("b");
    assertThat(store.leaseReady(T0, LEASE, 5, Set.of())).hasSize(1); // a; c is in the future
    assertThat(store.leaseReady(T0.plusSeconds(60), LEASE, 1, Set.of())).hasSize(1);
  }

  @Test
  @DisplayName("NFR-10: complete is a CAS on the lease token and keeps SKIPPED reason")
  void completeIsCas() {
    WorkKey k = add("a", "f", T0);
    WorkItem l = store.leaseReady(T0, LEASE, 1, Set.of()).get(0);
    WorkItem forged =
        new WorkItem(l.key(), l.firm(), l.publishId(), l.state(), 0, l.notBefore(), l.leaseUntil(), "bad", null);

    assertThat(store.complete(forged, ItemState.DONE)).isFalse();
    assertThat(store.complete(l.withSkipReason(SkipReason.FLAG_OFF), ItemState.SKIPPED)).isTrue();
    WorkItem after = store.find(k).orElseThrow();
    assertThat(after.state()).isEqualTo(ItemState.SKIPPED);
    assertThat(after.skipReason()).isEqualTo(SkipReason.FLAG_OFF);
    assertThat(after.leaseToken()).isNull();
    // already terminal: a second complete with the old token fails
    assertThat(store.complete(l, ItemState.DONE)).isFalse();
    assertThat(store.leaseReady(T0.plus(LEASE.multipliedBy(5)), LEASE, 5, Set.of())).isEmpty();
  }

  @Test
  @DisplayName("complete rejects a non-terminal target state")
  void completeRejectsNonTerminal() {
    add("a", "f", T0);
    WorkItem l = store.leaseReady(T0, LEASE, 1, Set.of()).get(0);
    assertThatThrownBy(() -> store.complete(l, ItemState.PENDING))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("FR-18 support: reschedule returns to PENDING with attempts and notBefore, CAS on token")
  void rescheduleIsCas() {
    WorkKey k = add("a", "f", T0);
    WorkItem l = store.leaseReady(T0, LEASE, 1, Set.of()).get(0);

    assertThat(store.reschedule(l, 2, T0.plusSeconds(10))).isTrue();
    assertThat(store.reschedule(l, 3, T0.plusSeconds(20))).isFalse(); // stale token now

    WorkItem p = store.find(k).orElseThrow();
    assertThat(p.state()).isEqualTo(ItemState.PENDING);
    assertThat(p.attempts()).isEqualTo(2);
    assertThat(store.leaseReady(T0.plusSeconds(9), LEASE, 1, Set.of())).isEmpty();
    assertThat(store.leaseReady(T0.plusSeconds(10), LEASE, 1, Set.of())).hasSize(1);
  }

  @Test
  @DisplayName("NFR-10: expired lease is re-leasable with a new token, the old holder is stale")
  void expiredLeaseReleased() {
    WorkKey k = add("a", "f", T0);
    WorkItem first = store.leaseReady(T0, LEASE, 1, Set.of()).get(0);

    WorkItem second = store.leaseReady(T0.plus(LEASE), LEASE, 1, Set.of()).get(0);

    assertThat(second.leaseToken()).isNotEqualTo(first.leaseToken());
    assertThat(store.complete(first, ItemState.DONE)).isFalse();
    assertThat(store.complete(second, ItemState.DONE)).isTrue();
    assertThat(store.find(k).orElseThrow().state()).isEqualTo(ItemState.DONE);
  }

  @Test
  @DisplayName("NFR-11 support: firmsWithQueuedWork lists firms with ready items only")
  void firmsWithQueuedWork() {
    add("a", "f1", T0);
    add("b", "f2", T0.plusSeconds(60));
    add("c", "f3", T0);
    store.leaseReady(T0, LEASE, 5, Set.of(new FirmId("f1"))); // leases c

    assertThat(store.firmsWithQueuedWork(T0)).containsExactly(new FirmId("f1"));
    assertThat(store.firmsWithQueuedWork(T0.plus(LEASE)))
        .containsExactlyInAnyOrder(new FirmId("f1"), new FirmId("f3")); // c lease expired
    assertThat(store.firmsWithQueuedWork(T0.plusSeconds(60)))
        .containsExactlyInAnyOrder(new FirmId("f1"), new FirmId("f2"), new FirmId("f3"));
  }

  @Test
  @DisplayName("FR-15: createIfAbsent keeps the first item")
  void createIfAbsent() {
    WorkKey k = add("a", "f", T0);
    boolean again = store.createIfAbsent(WorkItem.pending(k, new FirmId("other"), "p2", T0));
    assertThat(again).isFalse();
    assertThat(store.find(k).orElseThrow().publishId()).isEqualTo("p");
  }

  @Test
  @DisplayName("FR-21: countByPublish counts terminal states per publish, open items as non-terminal")
  void countByPublish() {
    add("a", "f", T0);
    add("b", "f", T0);
    add("c", "f", T0);
    add("d", "f", T0);
    List<WorkItem> leased = store.leaseReady(T0, LEASE, 3, Set.of());
    store.complete(leased.get(0), ItemState.DONE);
    store.complete(leased.get(1).withSkipReason(SkipReason.SUPERSEDED), ItemState.SKIPPED);
    store.complete(leased.get(2), ItemState.DEAD_LETTERED);
    store.createIfAbsent(
        WorkItem.pending(
            new WorkKey(new EfId("z"), new TemplateId("t"), new VersionId("v1")),
            new FirmId("f"), "other", T0));

    assertThat(store.countByPublish("p"))
        .isEqualTo(new com.caseware.fanout.domain.PublishCounts(1, 1, 1, 1));
    assertThat(store.countByPublish("other").nonTerminal()).isEqualTo(1);
    assertThat(store.countByPublish("none").terminal()).isZero();
  }
}
