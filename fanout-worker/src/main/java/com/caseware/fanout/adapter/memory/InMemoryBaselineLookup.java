package com.caseware.fanout.adapter.memory;

import com.caseware.fanout.domain.EfId;
import com.caseware.fanout.domain.EfRef;
import com.caseware.fanout.domain.Region;
import com.caseware.fanout.domain.TemplateId;
import com.caseware.fanout.domain.VersionId;
import com.caseware.fanout.graph.BaselineLookup;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Fake of the spec 01 stored baseline. Not an EF loader. */
public class InMemoryBaselineLookup implements BaselineLookup {

  private record Slot(Region region, EfId ef, TemplateId template) {}

  private final Map<Slot, VersionId> baselines = new ConcurrentHashMap<>();

  public void set(Region region, EfRef ef, TemplateId template, VersionId baseline) {
    baselines.put(new Slot(region, ef.ef(), template), baseline);
  }

  @Override
  public Optional<VersionId> baselineOf(Region region, EfRef ef, TemplateId templateId) {
    return Optional.ofNullable(baselines.get(new Slot(region, ef.ef(), templateId)));
  }
}
