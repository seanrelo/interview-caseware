package com.caseware.fanout.adapter.memory;

import com.caseware.fanout.domain.TemplateId;
import com.caseware.fanout.domain.VersionId;
import com.caseware.fanout.summary.BaselinesInUse;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryBaselinesInUse implements BaselinesInUse {
  private final Map<TemplateId, Set<VersionId>> byTemplate = new ConcurrentHashMap<>();

  public void register(TemplateId template, VersionId baseline) {
    byTemplate.computeIfAbsent(template, k -> ConcurrentHashMap.newKeySet()).add(baseline);
  }

  @Override
  public Set<VersionId> baselines(TemplateId template) {
    return Set.copyOf(byTemplate.getOrDefault(template, Set.of()));
  }
}
