package com.caseware.fanout.summary.eval;

import com.caseware.fanout.adapter.memory.FakeModelClient;
import com.caseware.fanout.summary.ModelClient;
import com.caseware.fanout.summary.ModelId;
import com.caseware.fanout.summary.PromptVersion;
import com.caseware.fanout.summary.SummaryProperties;
import com.caseware.fanout.summary.SummaryValidator;
import java.io.IOException;
import java.io.PrintStream;
import java.util.List;

/** CI entry point: exit code 0 when both rates are within thresholds, 1 above them, 2 on a usage or load error. */
public final class EvalRunner {
  public static final String DEFAULT_RESOURCE = "summary-eval/cases.txt";

  private EvalRunner() {}

  /** Args (all optional): unsupportedMax omissionMax. Defaults come from SummaryProperties. Uses the fake model only. */
  public static void main(String[] args) {
    SummaryProperties props = new SummaryProperties(new ModelId("fake-model-2026-01"), new PromptVersion("eval-v1"));
    double u = props.unsupportedMax();
    double o = props.omissionMax();
    try {
      if (args.length > 0) {
        u = Double.parseDouble(args[0]);
      }
      if (args.length > 1) {
        o = Double.parseDouble(args[1]);
      }
    } catch (NumberFormatException e) {
      System.err.println("usage: EvalRunner [unsupportedMax omissionMax]");
      System.exit(2);
    }
    System.exit(run(DEFAULT_RESOURCE, new FakeModelClient(), props, u, o, System.out));
  }

  public static int run(String resource, ModelClient client, SummaryProperties props, double unsupportedMax, double omissionMax, PrintStream out) {
    List<EvalCase> cases;
    try {
      cases = EvalCaseLoader.loadResource(resource);
    } catch (IOException | RuntimeException e) {
      out.println("cannot load eval set: " + e.getMessage());
      return 2;
    }
    return run(cases, client, props, unsupportedMax, omissionMax, out);
  }

  public static int run(List<EvalCase> cases, ModelClient client, SummaryProperties props, double unsupportedMax, double omissionMax, PrintStream out) {
    EvalReport r = new EvalHarness(new SummaryValidator()).run(cases, client, props.pinnedModel(), props.prompt(), unsupportedMax, omissionMax);
    out.println("model=" + r.model().value() + " prompt=" + r.prompt().value() + " cases=" + r.cases());
    out.println("unsupported " + r.unsupported() + "/" + r.statements() + " = " + r.unsupportedRate() + " (max " + unsupportedMax + ")");
    out.println("omitted " + r.omitted() + "/" + r.expectedItems() + " = " + r.omissionRate() + " (max " + omissionMax + ")");
    r.failures().forEach(f -> out.println("FAIL " + f));
    out.println(r.passed() ? "PASSED" : "FAILED");
    return r.passed() ? 0 : 1;
  }
}
