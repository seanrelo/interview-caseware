package com.caseware.fanout.adapter.memory;

import com.caseware.fanout.domain.PublishTotals;
import com.caseware.fanout.port.PublishProgressStore;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryPublishProgressStore implements PublishProgressStore {

  private final Map<String, PublishTotals> totals = new ConcurrentHashMap<>();

  @Override
  public void addAndSeal(String publishId, long created) {
    if (created < 0) {
      throw new IllegalArgumentException("created must be >= 0");
    }
    totals.computeIfAbsent(publishId, id -> new PublishTotals(id, created, true));
  }

  @Override
  public PublishTotals totals(String publishId) {
    PublishTotals t = totals.get(publishId);
    return t != null ? t : new PublishTotals(publishId, 0, false);
  }
}
