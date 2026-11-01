package com.caseware.fanout.adapter.memory;

import com.caseware.fanout.domain.EfRef;
import com.caseware.fanout.domain.Page;
import com.caseware.fanout.domain.Region;
import com.caseware.fanout.domain.TemplateId;
import com.caseware.fanout.domain.VersionId;
import com.caseware.fanout.graph.PendingTargetIndex;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;

/** Thread-safe reverse index; pages ordered by EF id, token is the last EF id returned. */
public class InMemoryPendingTargetIndex implements PendingTargetIndex {

  private record Bucket(Region region, TemplateId template, VersionId target) {}

  private final Map<Bucket, ConcurrentSkipListMap<String, EfRef>> buckets =
      new ConcurrentHashMap<>();

  @Override
  public void record(Region region, TemplateId templateId, VersionId pendingTarget, EfRef ef) {
    buckets
        .computeIfAbsent(new Bucket(region, templateId, pendingTarget), b -> new ConcurrentSkipListMap<>())
        .put(ef.ef().value(), ef);
  }

  @Override
  public void remove(Region region, TemplateId templateId, VersionId pendingTarget, EfRef ef) {
    ConcurrentSkipListMap<String, EfRef> b =
        buckets.get(new Bucket(region, templateId, pendingTarget));
    if (b != null) {
      b.remove(ef.ef().value());
    }
  }

  @Override
  public Page<EfRef> affectedBy(
      Region region,
      TemplateId templateId,
      VersionId withdrawn,
      Optional<String> pageToken,
      int pageSize) {
    if (pageSize <= 0) {
      throw new IllegalArgumentException("pageSize must be > 0");
    }
    ConcurrentSkipListMap<String, EfRef> all =
        buckets.get(new Bucket(region, templateId, withdrawn));
    if (all == null) {
      return new Page<>(List.of(), Optional.empty());
    }
    ConcurrentNavigableMap<String, EfRef> rest =
        pageToken.isPresent() ? all.tailMap(pageToken.get(), false) : all;
    List<EfRef> items = new ArrayList<>(pageSize);
    String last = null;
    for (Map.Entry<String, EfRef> e : rest.entrySet()) {
      if (items.size() == pageSize) {
        return new Page<>(items, Optional.of(last));
      }
      items.add(e.getValue());
      last = e.getKey();
    }
    return new Page<>(items, Optional.empty());
  }
}
