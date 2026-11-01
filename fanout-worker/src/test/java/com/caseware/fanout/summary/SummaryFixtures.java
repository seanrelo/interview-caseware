package com.caseware.fanout.summary;

import com.caseware.fanout.domain.TemplateId;
import com.caseware.fanout.domain.VersionId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

final class SummaryFixtures {
  static final Instant T0 = Instant.parse("2026-11-01T10:00:00Z");
  static final ModelId MODEL = new ModelId("model-2026-05-01");
  static final PromptVersion PROMPT = new PromptVersion("p3");

  private SummaryFixtures() {}

  static Clock clock() {
    return Clock.fixed(T0, ZoneOffset.UTC);
  }

  static PairKey pair(String base, String target) {
    return new PairKey(new TemplateId("T"), new VersionId(base), new VersionId(target));
  }

  static Diff diff(PairKey p) {
    return new Diff(
        p,
        List.of(
            new DiffItem("d1", "$.sections[0].title", "Revenue", "title renamed"),
            new DiffItem("d2", "/sections/1/fields/0", "Inventory", "field added")));
  }

  static SummaryRecord record(PairKey p, String gen) {
    return SummaryRecord.create(
        gen, p, List.of(new Statement("Revenue title renamed.", List.of("d1"))), diff(p), MODEL, PROMPT, T0);
  }
}
