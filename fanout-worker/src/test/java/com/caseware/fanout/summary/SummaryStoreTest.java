package com.caseware.fanout.summary;

import static com.caseware.fanout.summary.SummaryFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.caseware.fanout.adapter.memory.InMemorySummaryStore;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SummaryStoreTest {
  private final InMemorySummaryStore store = new InMemorySummaryStore();
  private final PairKey p = pair("v1", "v2");

  @Test
  @DisplayName("S12 NFR-15: SummaryStore exposes no update or delete method")
  void noMutator() {
    List<String> names = Arrays.stream(SummaryStore.class.getDeclaredMethods()).map(Method::getName).toList();
    assertThat(names).containsExactlyInAnyOrder("current", "get", "history", "append", "moveCurrentIfAbsent", "supersede");
    assertThat(names).noneMatch(n -> n.startsWith("update") || n.startsWith("delete") || n.startsWith("remove") || n.startsWith("put") || n.startsWith("set"));
  }

  @Test
  @DisplayName("S5 NFR-27: append is idempotent by (pair, generationId); first record wins")
  void idempotentAppend() {
    SummaryRecord first = store.append(record(p, "g1"));
    SummaryRecord dup = SummaryRecord.create("g1", p, List.of(new Statement("Other text.", List.of("d2"))), diff(p), MODEL, PROMPT, T0);
    assertThat(store.append(dup)).isSameAs(first);
    assertThat(store.history(p)).containsExactly(first);
  }

  @Test
  @DisplayName("S5 FR-36: moveCurrentIfAbsent sets the pointer once")
  void pointerOnce() {
    store.append(record(p, "g1"));
    store.append(record(p, "g2"));
    assertThat(store.current(p)).isEmpty();
    assertThat(store.moveCurrentIfAbsent(p, "g1")).isTrue();
    assertThat(store.moveCurrentIfAbsent(p, "g2")).isFalse();
    assertThat(store.current(p)).hasValueSatisfying(r -> assertThat(r.generationId()).isEqualTo("g1"));
    assertThatThrownBy(() -> store.moveCurrentIfAbsent(p, "nope")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("S12 NFR-15 NFR-16: supersede moves the pointer, the old record stays readable")
  void supersedeKeepsHistory() {
    SummaryRecord g1 = store.append(record(p, "g1"));
    store.moveCurrentIfAbsent(p, "g1");
    SummaryRecord g2 = store.append(record(p, "g2"));
    store.supersede(p, "g2");
    assertThat(store.current(p)).contains(g2);
    assertThat(store.get(p, "g1")).contains(g1);
    assertThat(store.history(p)).containsExactly(g1, g2);
  }

  @Test
  @DisplayName("S12: history is an unmodifiable snapshot")
  void historySnapshot() {
    store.append(record(p, "g1"));
    assertThatThrownBy(() -> store.history(p).clear()).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  @DisplayName("S9 NFR-15: current() detects tampering against the index hash")
  void tamper() {
    store.append(record(p, "g1"));
    store.moveCurrentIfAbsent(p, "g1");
    SummaryRecord forged = SummaryRecord.create("g1", p, List.of(new Statement("Forged.", List.of("d1"))), diff(p), MODEL, PROMPT, T0);
    store.tamperForTest(p, "g1", forged);
    assertThatThrownBy(() -> store.current(p)).isInstanceOf(SummaryTamperedException.class);
  }

  @Test
  @DisplayName("FR-36: unknown pair has no summary")
  void unknown() {
    assertThat(store.current(p)).isEmpty();
    assertThat(store.history(p)).isEmpty();
    assertThat(store.get(p, "x")).isEmpty();
  }
}
