package com.caseware.fanout.summary.eval;

import com.caseware.fanout.summary.ModelId;
import com.caseware.fanout.summary.PromptVersion;
import java.util.List;

/** Result over the whole set. The pinned model and prompt are recorded for reproducibility (NFR-17). */
public record EvalReport(
    int cases,
    int statements,
    int unsupported,
    int expectedItems,
    int omitted,
    double unsupportedRate,
    double omissionRate,
    boolean passed,
    List<String> failures,
    ModelId model,
    PromptVersion prompt) {
  public EvalReport {
    failures = List.copyOf(failures);
  }
}
