package com.caseware.fanout.adapter.memory;

import com.caseware.fanout.capture.EfCatalog;
import com.caseware.fanout.domain.EfRef;
import com.caseware.fanout.domain.Page;
import com.caseware.fanout.domain.Region;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentSkipListMap;

/** Seedable catalog, ordered by EF id; the page token is the last EF id returned. */
public class InMemoryEfCatalog implements EfCatalog {

  private final Map<Region, ConcurrentSkipListMap<String, EfRef>> byRegion =
      new EnumMap<>(Region.class);

  public InMemoryEfCatalog() {
    for (Region r : Region.values()) {
      byRegion.put(r, new ConcurrentSkipListMap<>());
    }
  }

  public void add(Region region, EfRef... efs) {
    for (EfRef ef : efs) {
      byRegion.get(region).put(ef.ef().value(), ef);
    }
  }

  @Override
  public Page<EfRef> listEfs(Region region, Optional<String> token, int pageSize) {
    if (pageSize < 1) {
      throw new IllegalArgumentException("pageSize must be >= 1");
    }
    ConcurrentSkipListMap<String, EfRef> m = byRegion.get(region);
    var tail = token.isPresent() ? m.tailMap(token.get(), false) : m;
    List<EfRef> items = tail.values().stream().limit(pageSize + 1L).toList();
    if (items.size() > pageSize) {
      List<EfRef> page = items.subList(0, pageSize);
      return new Page<>(page, Optional.of(page.get(pageSize - 1).ef().value()));
    }
    return new Page<>(items, Optional.empty());
  }
}
