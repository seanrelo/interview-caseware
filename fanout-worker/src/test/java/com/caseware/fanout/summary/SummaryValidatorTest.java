package com.caseware.fanout.summary;

import static com.caseware.fanout.summary.SummaryFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SummaryValidatorTest {
  private final SummaryValidator v = new SummaryValidator();
  private final Diff diff = diff(pair("v1", "v2"));

  private ValidationResult run(ModelStatement... s) {
    return v.validate(diff, List.of(s));
  }

  private static Violation.Kind kind(ValidationResult r) {
    return ((ValidationResult.Rejected) r).violations().get(0).kind();
  }

  @Test
  @DisplayName("S8 FR-39: statements with resolvable diff item ids are valid")
  void valid() {
    var r = run(new ModelStatement("Revenue title renamed.", List.of("d1")), new ModelStatement("Inventory field added.", List.of("d1", "d2")));
    assertThat(r).isInstanceOf(ValidationResult.Valid.class);
    assertThat(((ValidationResult.Valid) r).statements()).hasSize(2);
  }

  @Test
  @DisplayName("S10 FR-41: an empty summary is rejected")
  void empty() {
    assertThat(kind(run())).isEqualTo(Violation.Kind.NO_STATEMENTS);
  }

  @Test
  @DisplayName("S10 FR-41: a statement with no diff item id is rejected")
  void noIds() {
    assertThat(kind(run(new ModelStatement("Something changed.", List.of())))).isEqualTo(Violation.Kind.NO_DIFF_ITEM);
  }

  @Test
  @DisplayName("S10 FR-41: a statement citing an id absent from the diff is rejected")
  void unknownId() {
    assertThat(kind(run(new ModelStatement("Something changed.", List.of("d1", "zzz"))))).isEqualTo(Violation.Kind.UNKNOWN_DIFF_ITEM);
  }

  @Test
  @DisplayName("S10 FR-41: one bad statement rejects the whole summary")
  void oneBadRejectsAll() {
    var r = run(new ModelStatement("Fine.", List.of("d1")), new ModelStatement("Bad.", List.of()));
    assertThat(r).isInstanceOf(ValidationResult.Rejected.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Changed $.sections.title in the template.",
        "Changed $.a.b today.",
        "See /sections/1/fields for details.",
        "The value at sections[0] moved.",
        "The field order.items[3].price changed.",
        "Updated sections.title.text wording.",
        "Edited $.sections[0].title.",
        "Look at $.sections[0].title now"
      })
  @DisplayName("S7 FR-38: raw JSON path variants in text are rejected")
  void rawPaths(String text) {
    assertThat(kind(run(new ModelStatement(text, List.of("d1"))))).isEqualTo(Violation.Kind.RAW_PATH);
  }

  @Test
  @DisplayName("S7 FR-38: the exact diff path string is rejected even in odd context")
  void exactPath() {
    assertThat(kind(run(new ModelStatement("Moved /sections/1/fields/0 down", List.of("d2"))))).isEqualTo(Violation.Kind.RAW_PATH);
  }

  @ParameterizedTest
  @ValueSource(strings = {"The Revenue section title was renamed.", "Added a field to Inventory and/or Revenue (e.g. totals).", "Version 1.2 of the form changed."})
  @DisplayName("S7 FR-38: plain section-name prose is not flagged")
  void prose(String text) {
    assertThat(run(new ModelStatement(text, List.of("d1")))).isInstanceOf(ValidationResult.Valid.class);
  }
}
