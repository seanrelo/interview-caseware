package com.caseware.fanout.adapter.memory;

import com.caseware.fanout.domain.EfRef;
import com.caseware.fanout.domain.Page;
import com.caseware.fanout.domain.Region;
import com.caseware.fanout.domain.TemplateId;
import com.caseware.fanout.port.TemplateVersionIndex;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;

/** Thread-safe index; pages are ordered by EF id and the token is the last EF id returned. */
public class InMemoryTemplateVersionIndex implements TemplateVersionIndex {

  private record Bucket(Region region, TemplateId templateId) {}

  private final Map<Bucket, ConcurrentSkipListMap<String, EfRef>> buckets =
      new ConcurrentHashMap<>();

  public void register(Region region, TemplateId templateId, EfRef ef) {
    buckets
        .computeIfAbsent(new Bucket(region, templateId), b -> new ConcurrentSkipListMap<>())
        .put(ef.ef().value(), ef);
  }

  @Override
  public Page<EfRef> findEfsByTemplate(
      Region region, TemplateId templateId, Optional<String> pageToken, int pageSize) {
    if (pageSize <= 0) {
      throw new IllegalArgumentException("pageSize must be > 0");
    }
    ConcurrentSkipListMap<String, EfRef> all = buckets.get(new Bucket(region, templateId));
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
