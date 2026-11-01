package com.caseware.fanout.fault;

import com.caseware.fanout.domain.EfRef;
import com.caseware.fanout.domain.Page;
import com.caseware.fanout.domain.Region;
import com.caseware.fanout.domain.TemplateId;
import com.caseware.fanout.port.TemplateVersionIndex;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

/**
 * Decorator for {@link TemplateVersionIndex}: a latency hook per page call (no real sleep) and an
 * optional failure on the n-th call (1-based) to test a scan interrupted mid-paging.
 */
public class SlowIndex implements TemplateVersionIndex {

  private final TemplateVersionIndex delegate;
  private final AtomicInteger calls = new AtomicInteger();
  private volatile IntConsumer latencyHook = n -> {};
  private volatile int failOnCall = -1;

  public SlowIndex(TemplateVersionIndex delegate) {
    this.delegate = delegate;
  }

  /** Hook receives the 1-based call number before the page is read. */
  public SlowIndex latency(IntConsumer hook) {
    latencyHook = hook;
    return this;
  }

  /** The n-th call throws IllegalStateException (before reading); -1 disables. */
  public SlowIndex failOnCall(int n) {
    failOnCall = n;
    return this;
  }

  public int calls() {
    return calls.get();
  }

  @Override
  public Page<EfRef> findEfsByTemplate(
      Region region, TemplateId templateId, Optional<String> pageToken, int pageSize) {
    int n = calls.incrementAndGet();
    latencyHook.accept(n);
    if (n == failOnCall) {
      throw new IllegalStateException("injected index failure on call " + n);
    }
    return delegate.findEfsByTemplate(region, templateId, pageToken, pageSize);
  }
}
