package com.caseware.fanout.adapter.memory;

import com.caseware.fanout.domain.EfId;
import com.caseware.fanout.domain.TemplateId;
import com.caseware.fanout.domain.VersionId;
import com.caseware.fanout.status.Decision;
import com.caseware.fanout.status.DecisionResult;
import com.caseware.fanout.status.DecisionStore;
import com.caseware.fanout.status.DecisionType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Thread-safe, append-only. The first record of a key wins; a duplicate changes nothing. */
public class InMemoryDecisionStore implements DecisionStore {

  private record Slot(EfId ef, TemplateId template) {}

  private record Key(VersionId version, DecisionType type) {}

  private final Map<Slot, Map<Key, Decision>> bySlot = new ConcurrentHashMap<>();

  @Override
  public DecisionResult append(Decision d) {
    Map<Key, Decision> m =
        bySlot.computeIfAbsent(new Slot(d.ef().ef(), d.template()), k -> new ConcurrentHashMap<>());
    return m.putIfAbsent(new Key(d.version(), d.type()), d) == null
        ? DecisionResult.RECORDED
        : DecisionResult.DUPLICATE;
  }

  @Override
  public Set<VersionId> declined(EfId ef, TemplateId template) {
    return versions(ef, template, DecisionType.DECLINE);
  }

  @Override
  public Set<VersionId> accepted(EfId ef, TemplateId template) {
    return versions(ef, template, DecisionType.ACCEPT);
  }

  /** Read-only audit view of one EF's records, oldest first. */
  public List<Decision> history(EfId ef, TemplateId template) {
    List<Decision> out =
        new ArrayList<>(bySlot.getOrDefault(new Slot(ef, template), Map.of()).values());
    out.sort(Comparator.comparing(Decision::at));
    return out;
  }

  private Set<VersionId> versions(EfId ef, TemplateId template, DecisionType type) {
    Set<VersionId> out = ConcurrentHashMap.newKeySet();
    bySlot.getOrDefault(new Slot(ef, template), Map.of()).keySet().stream()
        .filter(k -> k.type() == type)
        .forEach(k -> out.add(k.version()));
    return Set.copyOf(out);
  }
}
