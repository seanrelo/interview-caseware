package com.caseware.fanout.web;

import com.caseware.fanout.summary.SummaryRecord;
import com.caseware.fanout.summary.SummaryView;
import java.util.List;

/**
 * Summary as sent to the client. The diff item path is never exposed (FR-38): only id, section
 * and change.
 */
public sealed interface SummaryBody {

  record Ready(
      String state,
      String label,
      String generationId,
      String generatedAt,
      String model,
      String promptVersion,
      String baseline,
      String target,
      List<StatementDto> statements,
      List<DiffItemDto> diff)
      implements SummaryBody {}

  record Generating(String state) implements SummaryBody {}

  record Unavailable(String state, String message) implements SummaryBody {}

  record StatementDto(String text, List<String> diffItemIds) {}

  record DiffItemDto(String id, String section, String change) {}

  static SummaryBody unavailable() {
    return new Unavailable("UNAVAILABLE", SummaryView.UNAVAILABLE);
  }

  static SummaryBody from(SummaryView view) {
    return switch (view) {
      case SummaryView.Ready r -> ready(r);
      case SummaryView.Generating g -> new Generating("GENERATING");
      case SummaryView.Unavailable u -> unavailable();
    };
  }

  private static SummaryBody ready(SummaryView.Ready view) {
    SummaryRecord s = view.summary();
    return new Ready(
        "READY",
        view.label(),
        s.generationId(),
        s.generatedAt().toString(),
        s.model().value(),
        s.prompt().value(),
        s.pair().baseline().value(),
        s.pair().target().value(),
        s.statements().stream()
            .map(st -> new StatementDto(st.text(), st.diffItemIds()))
            .toList(),
        s.diff().items().stream()
            .map(i -> new DiffItemDto(i.id(), i.sectionName(), i.change()))
            .toList());
  }
}
