package com.caseware.fanout.adapter.memory;

import com.caseware.fanout.summary.Diff;
import com.caseware.fanout.summary.ModelClient;
import com.caseware.fanout.summary.ModelException;
import com.caseware.fanout.summary.ModelOutput;
import com.caseware.fanout.summary.ModelRequest;
import com.caseware.fanout.summary.ModelStatement;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** Deterministic test double: one statement per diff item, naming the section and the change, never the path. */
public class FakeModelClient implements ModelClient {
  private final AtomicInteger calls = new AtomicInteger();
  private final AtomicInteger failures = new AtomicInteger();
  private final List<ModelRequest> captured = new CopyOnWriteArrayList<>();
  private volatile Consumer<ModelRequest> beforeGenerate = r -> {};
  private volatile Function<Diff, ModelOutput> override;

  /** The next n calls throw ModelException. */
  public void failNext(int n) {
    failures.set(n);
  }

  /** Hook run at the start of each call (block on a latch to simulate latency; no sleeping). */
  public void beforeGenerate(Consumer<ModelRequest> hook) {
    this.beforeGenerate = hook;
  }

  public void outputOverride(Function<Diff, ModelOutput> f) {
    this.override = f;
  }

  public int calls() {
    return calls.get();
  }

  public List<ModelRequest> captured() {
    return List.copyOf(captured);
  }

  @Override
  public ModelOutput generate(ModelRequest request) throws ModelException {
    calls.incrementAndGet();
    captured.add(request);
    beforeGenerate.accept(request);
    if (failures.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
      throw new ModelException("injected model failure");
    }
    Function<Diff, ModelOutput> o = override;
    if (o != null) {
      return o.apply(request.diff());
    }
    return new ModelOutput(
        request.diff().items().stream()
            .map(i -> new ModelStatement("In section " + i.sectionName() + ": " + i.change() + ".", List.of(i.id())))
            .toList());
  }
}
