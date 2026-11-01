package com.caseware.fanout.adapter.memory;

import com.caseware.fanout.summary.FailureKind;
import com.caseware.fanout.summary.PairKey;
import com.caseware.fanout.summary.SummaryMetrics;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public class InMemorySummaryMetrics implements SummaryMetrics {
  private final AtomicInteger generated = new AtomicInteger();
  private final Map<FailureKind, AtomicInteger> failed = new ConcurrentHashMap<>();
  private final List<Duration> latencies = new CopyOnWriteArrayList<>();

  @Override
  public void generated(PairKey pair) {
    generated.incrementAndGet();
  }

  @Override
  public void failed(FailureKind kind) {
    failed.computeIfAbsent(kind, k -> new AtomicInteger()).incrementAndGet();
  }

  @Override
  public void readyLatency(Duration latency) {
    latencies.add(latency);
  }

  public int generatedCount() {
    return generated.get();
  }

  public int failedCount(FailureKind kind) {
    AtomicInteger c = failed.get(kind);
    return c == null ? 0 : c.get();
  }

  public List<Duration> readyLatencies() {
    return List.copyOf(latencies);
  }
}
