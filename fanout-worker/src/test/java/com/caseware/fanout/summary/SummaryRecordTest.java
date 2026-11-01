package com.caseware.fanout.summary;

import static com.caseware.fanout.summary.SummaryFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SummaryRecordTest {
  private final PairKey p = pair("v1", "v2");

  @Test
  @DisplayName("S9 FR-40 NFR-16: record carries diff, model, prompt version, time and a SHA-256 hash")
  void roundTrip() {
    SummaryRecord r = record(p, "g1");
    assertThat(r.diff()).isEqualTo(diff(p));
    assertThat(r.model()).isEqualTo(MODEL);
    assertThat(r.prompt()).isEqualTo(PROMPT);
    assertThat(r.generatedAt()).isEqualTo(T0);
    assertThat(r.contentHash()).matches("[0-9a-f]{64}").isEqualTo(r.recomputeHash());
  }

  @Test
  @DisplayName("S9 NFR-15: a wrong hash is rejected at construction")
  void badHash() {
    SummaryRecord r = record(p, "g1");
    assertThatThrownBy(() -> new SummaryRecord("g1", p, r.statements(), r.diff(), MODEL, PROMPT, T0, "0".repeat(64), true))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("S9: hash changes with any content field")
  void hashCoversContent() {
    SummaryRecord a = record(p, "g1");
    SummaryRecord other = SummaryRecord.create("g1", p, List.of(new Statement("Different.", List.of("d1"))), diff(p), MODEL, PROMPT, T0);
    SummaryRecord later = SummaryRecord.create("g1", p, a.statements(), diff(p), MODEL, PROMPT, T0.plusSeconds(1));
    SummaryRecord model = SummaryRecord.create("g1", p, a.statements(), diff(p), new ModelId("model-x"), PROMPT, T0);
    assertThat(List.of(other.contentHash(), later.contentHash(), model.contentHash())).doesNotContain(a.contentHash()).doesNotHaveDuplicates();
  }

  @Test
  @DisplayName("FR-43: aiGenerated is always true")
  void aiAlways() {
    SummaryRecord r = record(p, "g1");
    assertThat(r.aiGenerated()).isTrue();
    assertThatThrownBy(() -> new SummaryRecord("g1", p, r.statements(), r.diff(), MODEL, PROMPT, T0, r.contentHash(), false))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("S12 NFR-15: stored lists are defensive copies and unmodifiable")
  void immutableLists() {
    List<Statement> src = new ArrayList<>(List.of(new Statement("x", new ArrayList<>(List.of("d1")))));
    SummaryRecord r = SummaryRecord.create("g1", p, src, diff(p), MODEL, PROMPT, T0);
    src.clear();
    assertThat(r.statements()).hasSize(1);
    assertThatThrownBy(() -> r.statements().add(new Statement("y", List.of("d1")))).isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> r.diff().items().clear()).isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> r.statements().get(0).diffItemIds().add("d2")).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  @DisplayName("FR-36: PairKey has no firm or EF field and needs distinct versions")
  void pairKey() {
    assertThat(PairKey.class.getRecordComponents()).extracting(c -> c.getName()).containsExactly("template", "baseline", "target");
    assertThatThrownBy(() -> pair("v1", "v1")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("FR-41: Statement needs an id; a diff rejects duplicate item ids")
  void guards() {
    assertThatThrownBy(() -> new Statement("x", List.of())).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Diff(p, List.of(new DiffItem("a", "p", "s", "c"), new DiffItem("a", "q", "s", "c"))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("NFR-17: alias-like model ids are rejected")
  void alias() {
    for (String bad : new String[] {"latest", "LATEST", "default", " ", "", "model-latest", "family:latest"}) {
      assertThatThrownBy(() -> new ModelId(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(new ModelId("model-2026-05-01").value()).isEqualTo("model-2026-05-01");
    assertThatThrownBy(() -> new SummaryProperties(MODEL, PROMPT, 0, java.time.Duration.ofMinutes(1), java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(2), 0.01, 0.05))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(new SummaryProperties(MODEL, PROMPT).maxAttempts()).isEqualTo(5);
  }

  @Test
  @DisplayName("S9: Instant is part of the record")
  void instantPresent() {
    assertThat(record(p, "g1").generatedAt()).isInstanceOf(Instant.class);
  }
}
