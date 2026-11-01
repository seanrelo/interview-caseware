package com.caseware.fanout.summary;

import static com.caseware.fanout.summary.SummaryFixtures.diff;
import static com.caseware.fanout.summary.SummaryFixtures.pair;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Regression tests for the raw-path heuristic boundaries (QA-5, QA-6). */
class SummaryValidatorRegressionTest {
  private final SummaryValidator v = new SummaryValidator();
  private final Diff d = diff(pair("v1", "v2"));

  @ParameterizedTest
  @ValueSource(strings = {"The clause now applies to the U.S.A. market.", "Qualification changed to a Ph.D requirement.", "Sentence ends.The next one follows."})
  @DisplayName("QA-5 FR-38 FR-42: legitimate prose must not be rejected as a raw path")
  void legitimateProseNotRejected(String text) {
    assertThat(v.validate(d, List.of(new ModelStatement(text, List.of("d1"))))).isInstanceOf(ValidationResult.Valid.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"Edited clauses.0.title heading.", "Changed fields.1 wording.", "Edited fields.2.label wording."})
  @DisplayName("QA-6 FR-38: dotted path with numeric segments must be rejected")
  void numericDottedPathRejected(String text) {
    assertThat(v.validate(d, List.of(new ModelStatement(text, List.of("d1"))))).isInstanceOf(ValidationResult.Rejected.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"Updated to 2.0.1 of the form.", "Version 1.2 applies.", "Added and/or removed items (e.g. totals, i.e. all).", "Meetings at 9 a.m. and 5 p.m. daily.", "Reviewed by Mr.Smith today."})
  @DisplayName("FR-38: version numbers, abbreviations and uppercase sentence joins pass")
  void boundaryAllowed(String text) {
    assertThat(v.validate(d, List.of(new ModelStatement(text, List.of("d1"))))).isInstanceOf(ValidationResult.Valid.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"Edited clauses.title wording.", "Edited a.b.c wording.", "Edited sections.0 wording.", "Edited 3.title wording."})
  @DisplayName("FR-38: lowercase two-segment, 3+ segment and mixed numeric dotted tokens are rejected")
  void boundaryRejected(String text) {
    assertThat(v.validate(d, List.of(new ModelStatement(text, List.of("d1"))))).isInstanceOf(ValidationResult.Rejected.class);
  }
}
