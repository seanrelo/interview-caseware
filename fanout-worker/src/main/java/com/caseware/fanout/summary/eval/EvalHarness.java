package com.caseware.fanout.summary.eval;

import com.caseware.fanout.summary.ModelClient;
import com.caseware.fanout.summary.ModelException;
import com.caseware.fanout.summary.ModelId;
import com.caseware.fanout.summary.ModelOutput;
import com.caseware.fanout.summary.ModelRequest;
import com.caseware.fanout.summary.ModelStatement;
import com.caseware.fanout.summary.PromptVersion;
import com.caseware.fanout.summary.SummaryValidator;
import com.caseware.fanout.summary.ValidationResult;
import com.caseware.fanout.summary.Violation;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Measures unsupported and omission rates over a labelled set (NFR-18, NFR-19, ADR 0012 item 7).
 * A rate strictly greater than its threshold fails; equal passes.
 */
public final class EvalHarness {
  private static final double EPS = 1e-12;
  private final SummaryValidator validator;

  public EvalHarness(SummaryValidator validator) {
    this.validator = validator;
  }

  public EvalReport run(
      List<EvalCase> cases,
      ModelClient client,
      ModelId model,
      PromptVersion prompt,
      double unsupportedMax,
      double omissionMax) {
    int statements = 0;
    int unsupported = 0;
    int expectedItems = 0;
    int omitted = 0;
    List<String> failures = new ArrayList<>();
    for (EvalCase c : cases) {
      expectedItems += c.expectedItemIds().size();
      ModelOutput out;
      try {
        out = client.generate(new ModelRequest(model, prompt, c.diff()));
      } catch (ModelException | RuntimeException e) {
        omitted += c.expectedItemIds().size();
        failures.add("case " + c.name() + ": model call failed: " + e.getMessage());
        continue;
      }
      List<ModelStatement> list = out.statements();
      statements += list.size();
      Set<Integer> invalid = new HashSet<>();
      if (validator.validate(c.diff(), list) instanceof ValidationResult.Rejected r) {
        for (Violation v : r.violations()) {
          if (v.statementIndex() >= 0) {
            invalid.add(v.statementIndex());
          }
        }
      }
      Set<String> supported = c.allSupportedItemIds();
      Set<String> cited = new HashSet<>();
      for (int i = 0; i < list.size(); i++) {
        ModelStatement s = list.get(i);
        cited.addAll(s.diffItemIds());
        if (invalid.contains(i) || s.diffItemIds().isEmpty() || !supported.containsAll(s.diffItemIds())) {
          unsupported++;
        }
      }
      for (String id : c.expectedItemIds()) {
        if (!cited.contains(id)) {
          omitted++;
        }
      }
    }
    double uRate = statements == 0 ? 0.0 : (double) unsupported / statements;
    double oRate = expectedItems == 0 ? 0.0 : (double) omitted / expectedItems;
    if (uRate > unsupportedMax + EPS) {
      failures.add("unsupported rate " + uRate + " > " + unsupportedMax + " (" + unsupported + "/" + statements + ")");
    }
    if (oRate > omissionMax + EPS) {
      failures.add("omission rate " + oRate + " > " + omissionMax + " (" + omitted + "/" + expectedItems + ")");
    }
    return new EvalReport(
        cases.size(), statements, unsupported, expectedItems, omitted, uRate, oRate, failures.isEmpty(), failures, model, prompt);
  }
}
