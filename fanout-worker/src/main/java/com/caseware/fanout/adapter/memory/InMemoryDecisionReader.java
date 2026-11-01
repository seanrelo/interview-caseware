package com.caseware.fanout.adapter.memory;

import com.caseware.fanout.domain.EfId;
import com.caseware.fanout.domain.TemplateId;
import com.caseware.fanout.domain.VersionId;
import com.caseware.fanout.status.DecisionReader;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Test fake of the read side; spec 04 T3 adds the append-only store that implements the port. */
public class InMemoryDecisionReader implements DecisionReader {
  private record Key(EfId ef, TemplateId template) {}

  private final Map<Key, Set<VersionId>> declined = new ConcurrentHashMap<>();
  private final Map<Key, Set<VersionId>> accepted = new ConcurrentHashMap<>();

  public void decline(EfId ef, TemplateId t, VersionId v) {
    declined.computeIfAbsent(new Key(ef, t), k -> ConcurrentHashMap.newKeySet()).add(v);
  }

  public void accept(EfId ef, TemplateId t, VersionId v) {
    accepted.computeIfAbsent(new Key(ef, t), k -> ConcurrentHashMap.newKeySet()).add(v);
  }

  @Override
  public Set<VersionId> declined(EfId ef, TemplateId t) {
    return Set.copyOf(declined.getOrDefault(new Key(ef, t), Set.of()));
  }

  @Override
  public Set<VersionId> accepted(EfId ef, TemplateId t) {
    return Set.copyOf(accepted.getOrDefault(new Key(ef, t), Set.of()));
  }
}
