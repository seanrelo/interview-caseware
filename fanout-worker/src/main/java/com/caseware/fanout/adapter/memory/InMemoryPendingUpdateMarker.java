package com.caseware.fanout.adapter.memory;

import com.caseware.fanout.domain.EfId;
import com.caseware.fanout.domain.TemplateId;
import com.caseware.fanout.domain.VersionId;
import com.caseware.fanout.domain.WorkKey;
import com.caseware.fanout.graph.EligibleResult;
import com.caseware.fanout.port.MarkResult;
import com.caseware.fanout.port.PendingUpdateMarker;
import com.caseware.fanout.port.VersionGraphReader;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory status store. Writes are version-monotonic compare-and-set in graph order (ADR 0006
 * clarifications 2 and 3), per (EF, template). Counts calls for tests.
 */
public class InMemoryPendingUpdateMarker implements PendingUpdateMarker {

  /** Stored status of one (EF, template): the target and whether it is unknown. */
  public record Status(VersionId target, boolean unknown, boolean upToDate) {
    public Status(VersionId target, boolean unknown) {
      this(target, unknown, false);
    }
  }

  private record Slot(EfId ef, TemplateId template) {}

  private final VersionGraphReader graph;
  private final Map<Slot, Status> states = new ConcurrentHashMap<>();
  private final Map<WorkKey, AtomicInteger> recordCalls = new ConcurrentHashMap<>();
  private final AtomicInteger totalRecordCalls = new AtomicInteger();
  private final AtomicInteger totalReevaluateCalls = new AtomicInteger();

  public InMemoryPendingUpdateMarker(VersionGraphReader graph) {
    this.graph = graph;
  }

  @Override
  public MarkResult record(WorkKey key) {
    totalRecordCalls.incrementAndGet();
    recordCalls.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
    MarkResult[] result = new MarkResult[1];
    states.compute(
        slot(key),
        (s, cur) -> {
          if (cur == null
              || graph.isNewer(key.templateId(), key.targetVersion(), cur.target())
              || (cur.unknown() && key.targetVersion().equals(cur.target()))) {
            result[0] = MarkResult.APPLIED;
            return new Status(key.targetVersion(), false);
          }
          result[0] = MarkResult.UNCHANGED;
          return cur;
        });
    return result[0];
  }

  @Override
  public void markUnknown(WorkKey key, String internalReason) {
    states.compute(
        slot(key),
        (s, cur) -> {
          if (cur != null
              && !cur.unknown()
              && graph.isNewer(key.templateId(), cur.target(), key.targetVersion())) {
            return cur;
          }
          return new Status(key.targetVersion(), true);
        });
  }

  @Override
  public MarkResult reevaluate(WorkKey withdrawn, EligibleResult result) {
    totalReevaluateCalls.incrementAndGet();
    MarkResult[] out = {MarkResult.UNCHANGED};
    states.compute(
        slot(withdrawn),
        (s, cur) -> {
          if (cur == null || cur.upToDate() || !cur.target().equals(withdrawn.targetVersion())) {
            return cur;
          }
          Status next =
              switch (result) {
                case EligibleResult.Eligible e -> new Status(e.version(), false);
                // Tombstone keeps the cleared target so a stale record() of it is not re-applied.
                case EligibleResult.UpToDate u -> new Status(cur.target(), false, true);
                case EligibleResult.Unknown u -> new Status(cur.target(), true);
              };
          if (next.equals(cur)) {
            return cur;
          }
          out[0] = MarkResult.APPLIED;
          return next;
        });
    return out[0];
  }

  public Optional<Status> status(EfId ef, TemplateId template) {
    return Optional.ofNullable(states.get(new Slot(ef, template)));
  }

  public Map<EfId, Status> snapshot(TemplateId template) {
    Map<EfId, Status> out = new java.util.HashMap<>();
    states.forEach((s, v) -> {
      if (s.template().equals(template)) {
        out.put(s.ef(), v);
      }
    });
    return out;
  }

  public int recordCalls(WorkKey key) {
    AtomicInteger n = recordCalls.get(key);
    return n == null ? 0 : n.get();
  }

  public int totalReevaluateCalls() {
    return totalReevaluateCalls.get();
  }

  public int totalRecordCalls() {
    return totalRecordCalls.get();
  }

  private static Slot slot(WorkKey key) {
    return new Slot(key.ef(), key.templateId());
  }
}
