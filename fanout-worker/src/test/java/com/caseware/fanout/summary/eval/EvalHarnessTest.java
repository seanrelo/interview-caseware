package com.caseware.fanout.summary.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.caseware.fanout.adapter.memory.FakeModelClient;
import com.caseware.fanout.domain.TemplateId;
import com.caseware.fanout.domain.VersionId;
import com.caseware.fanout.summary.Diff;
import com.caseware.fanout.summary.DiffItem;
import com.caseware.fanout.summary.ModelId;
import com.caseware.fanout.summary.ModelOutput;
import com.caseware.fanout.summary.ModelStatement;
import com.caseware.fanout.summary.PairKey;
import com.caseware.fanout.summary.PromptVersion;
import com.caseware.fanout.summary.SummaryProperties;
import com.caseware.fanout.summary.SummaryValidator;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EvalHarnessTest {
  private static final ModelId MODEL = new ModelId("model-2026-01-15");
  private static final PromptVersion PROMPT = new PromptVersion("p-7");
  private final EvalHarness harness = new EvalHarness(new SummaryValidator());
  private final SummaryProperties props = new SummaryProperties(MODEL, PROMPT);

  private static PairKey pair() {
    return new PairKey(new TemplateId("T"), new VersionId("v1"), new VersionId("v2"));
  }

  private static EvalCase caseOf(String name, int n) {
    List<DiffItem> items = new ArrayList<>();
    Set<String> ids = new java.util.LinkedHashSet<>();
    for (int i = 0; i < n; i++) {
      items.add(new DiffItem("i" + i, "$.s[" + i + "]", "Sec" + i, "changed"));
      ids.add("i" + i);
    }
    return new EvalCase(name, new Diff(pair(), items), Map.of("all", ids), ids);
  }

  private static List<EvalCase> fixture() throws IOException {
    return EvalCaseLoader.loadResource(EvalRunner.DEFAULT_RESOURCE);
  }

  @Test
  @DisplayName("E1 NFR-18 NFR-19: fixture has at least 6 cases and a good model passes with rates 0")
  void goodModelPasses() throws IOException {
    List<EvalCase> cases = fixture();
    assertThat(cases).hasSizeGreaterThanOrEqualTo(6);
    EvalReport r = harness.run(cases, new FakeModelClient(), MODEL, PROMPT, 0.01, 0.05);
    assertThat(r.passed()).isTrue();
    assertThat(r.unsupported()).isZero();
    assertThat(r.omitted()).isZero();
    assertThat(r.unsupportedRate()).isZero();
    assertThat(r.omissionRate()).isZero();
    assertThat(r.statements()).isEqualTo(r.expectedItems()).isPositive();
    assertThat(runner(new FakeModelClient())).isZero();
  }

  @Test
  @DisplayName("E1 NFR-18: citing an item outside the supported set is unsupported and exits non-zero")
  void outsideSupportedSetFails() throws IOException {
    FakeModelClient bad = new FakeModelClient();
    bad.outputOverride(d -> new ModelOutput(d.items().stream()
        .map(i -> new ModelStatement("In section " + i.sectionName() + " something changed.", List.of(i.id())))
        .toList()));
    // narrow the label: only i0 supported, but i1 expected-and-cited
    Diff d = caseOf("x", 2).diff();
    EvalCase narrow = new EvalCase("narrow", d, Map.of("s", Set.of("i0")), Set.of("i0"));
    EvalReport r = harness.run(List.of(narrow), bad, MODEL, PROMPT, 0.01, 0.05);
    assertThat(r.unsupported()).isEqualTo(1);
    assertThat(r.statements()).isEqualTo(2);
    assertThat(r.unsupportedRate()).isEqualTo(0.5);
    assertThat(r.passed()).isFalse();
    assertThat(runner(List.of(narrow), bad)).isNotZero();
  }

  @Test
  @DisplayName("E1 NFR-19: a model omitting half the items fails the omission threshold with exact counts")
  void omittingHalfFails() throws IOException {
    FakeModelClient half = new FakeModelClient();
    half.outputOverride(d -> new ModelOutput(d.items().stream()
        .limit(d.items().size() / 2)
        .map(i -> new ModelStatement("In section " + i.sectionName() + ": changed.", List.of(i.id())))
        .toList()));
    EvalReport r = harness.run(List.of(caseOf("c", 10)), half, MODEL, PROMPT, 0.01, 0.05);
    assertThat(r.omitted()).isEqualTo(5);
    assertThat(r.expectedItems()).isEqualTo(10);
    assertThat(r.omissionRate()).isEqualTo(0.5);
    assertThat(r.unsupported()).isZero();
    assertThat(r.passed()).isFalse();
    assertThat(runner(List.of(caseOf("c", 10)), half)).isNotZero();
  }

  @Test
  @DisplayName("E1 NFR-18 FR-39: a statement leaking a raw path or citing nothing is unsupported")
  void rawPathAndNoCitationAreUnsupported() {
    FakeModelClient leak = new FakeModelClient();
    leak.outputOverride(d -> new ModelOutput(List.of(
        new ModelStatement("Changed $.s[0] here.", List.of("i0")),
        new ModelStatement("Something changed.", List.of()),
        new ModelStatement("In section Sec2: changed.", List.of("i1")))));
    EvalReport r = harness.run(List.of(caseOf("c", 2)), leak, MODEL, PROMPT, 0.01, 0.05);
    assertThat(r.statements()).isEqualTo(3);
    assertThat(r.unsupported()).isEqualTo(2);
    assertThat(r.omitted()).isZero();
    assertThat(r.passed()).isFalse();
  }

  @Test
  @DisplayName("NFR-18: 2 of 100 unsupported = 0.02 fails at 0.01")
  void twoOfHundredFails() {
    EvalReport r = harness.run(List.of(caseOf("c", 100)), withBad(2), MODEL, PROMPT, 0.01, 0.05);
    assertThat(r.statements()).isEqualTo(100);
    assertThat(r.unsupported()).isEqualTo(2);
    assertThat(r.unsupportedRate()).isEqualTo(0.02);
    assertThat(r.passed()).isFalse();
  }

  @Test
  @DisplayName("NFR-18: 1 of 100 unsupported = 0.01 passes at the boundary (only > fails)")
  void oneOfHundredPasses() {
    EvalReport r = harness.run(List.of(caseOf("c", 100)), withBad(1), MODEL, PROMPT, 0.01, 0.05);
    assertThat(r.unsupported()).isEqualTo(1);
    assertThat(r.unsupportedRate()).isEqualTo(0.01);
    assertThat(r.passed()).isTrue();
  }

  @Test
  @DisplayName("NFR-19: 5 of 100 omitted = 0.05 passes, 6 of 100 fails")
  void omissionBoundary() {
    assertThat(harness.run(List.of(caseOf("c", 100)), omitting(5), MODEL, PROMPT, 0.01, 0.05).passed()).isTrue();
    EvalReport r = harness.run(List.of(caseOf("c", 100)), omitting(6), MODEL, PROMPT, 0.01, 0.05);
    assertThat(r.omissionRate()).isEqualTo(0.06);
    assertThat(r.passed()).isFalse();
  }

  @Test
  @DisplayName("NFR-17: an alias model id is rejected before any evaluation")
  void aliasRejected() {
    assertThatThrownBy(() -> new ModelId("latest")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ModelId("vendor-model-latest")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("NFR-17: the pinned model and prompt are recorded in the report and are the only request inputs")
  void pinnedRecorded() throws IOException {
    FakeModelClient fake = new FakeModelClient();
    EvalReport r = harness.run(fixture(), fake, MODEL, PROMPT, 0.01, 0.05);
    assertThat(r.model()).isEqualTo(MODEL);
    assertThat(r.prompt()).isEqualTo(PROMPT);
    assertThat(fake.captured()).isNotEmpty().allSatisfy(q -> {
      assertThat(q.model()).isEqualTo(MODEL);
      assertThat(q.prompt()).isEqualTo(PROMPT);
    });
    assertThat(r.cases()).isEqualTo(fake.calls());
  }

  @Test
  @DisplayName("NFR-19 FR-41: a ModelException counts the case's items as omitted and is reported, not swallowed")
  void modelExceptionReported() {
    FakeModelClient flaky = new FakeModelClient();
    flaky.failNext(1);
    EvalReport r = harness.run(List.of(caseOf("first", 4), caseOf("second", 4)), flaky, MODEL, PROMPT, 0.01, 0.05);
    assertThat(r.omitted()).isEqualTo(4);
    assertThat(r.expectedItems()).isEqualTo(8);
    assertThat(r.statements()).isEqualTo(4);
    assertThat(r.passed()).isFalse();
    assertThat(r.failures()).anyMatch(f -> f.contains("first") && f.contains("injected model failure"));
  }

  @Test
  @DisplayName("NFR-18: a failing exception case never passes even with lenient thresholds")
  void exceptionFailsEvenWithLenientThresholds() {
    FakeModelClient flaky = new FakeModelClient();
    flaky.failNext(1);
    EvalReport r = harness.run(List.of(caseOf("only", 1)), flaky, MODEL, PROMPT, 1.0, 1.0);
    assertThat(r.passed()).isFalse();
  }

  @Test
  @DisplayName("loader: malformed fixture is rejected")
  void loaderRejectsMalformed() {
    assertThatThrownBy(() -> EvalCaseLoader.load(new java.io.StringReader("bogus x\n"))).isInstanceOf(IOException.class);
    assertThatThrownBy(() -> EvalCaseLoader.load(new java.io.StringReader("case a\npair T v1 v2\n"))).isInstanceOf(IOException.class);
  }

  private int runner(FakeModelClient client) {
    return EvalRunner.run(EvalRunner.DEFAULT_RESOURCE, client, props, 0.01, 0.05, sink());
  }

  private int runner(List<EvalCase> cases, FakeModelClient client) {
    return EvalRunner.run(cases, client, props, 0.01, 0.05, sink());
  }

  private static PrintStream sink() {
    return new PrintStream(new ByteArrayOutputStream());
  }

  /** One statement per item; the first k cite no item (unsupported). */
  private static FakeModelClient withBad(int k) {
    FakeModelClient f = new FakeModelClient();
    f.outputOverride(d -> {
      List<ModelStatement> out = new ArrayList<>();
      for (int i = 0; i < d.items().size(); i++) {
        DiffItem it = d.items().get(i);
        out.add(new ModelStatement("In section " + it.sectionName() + ": changed.", i < k ? List.of() : List.of(it.id())));
      }
      return new ModelOutput(out);
    });
    return f;
  }

  /** Cites all items but omits the last k. */
  private static FakeModelClient omitting(int k) {
    FakeModelClient f = new FakeModelClient();
    f.outputOverride(d -> new ModelOutput(d.items().stream()
        .limit(d.items().size() - k)
        .map(i -> new ModelStatement("In section " + i.sectionName() + ": changed.", List.of(i.id())))
        .toList()));
    return f;
  }
}
