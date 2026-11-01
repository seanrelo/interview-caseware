package com.caseware.fanout.app;

import com.caseware.fanout.domain.EfRef;
import com.caseware.fanout.domain.Page;
import com.caseware.fanout.domain.PublishEvent;
import com.caseware.fanout.domain.Region;
import com.caseware.fanout.domain.WorkItem;
import com.caseware.fanout.domain.WorkKey;
import com.caseware.fanout.port.PublishProgressStore;
import com.caseware.fanout.port.TemplateVersionIndex;
import com.caseware.fanout.port.WorkItemStore;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns one publish event into one work item per affected EF of one region (FR-14, FR-15). One
 * instance per regional cell (NFR-35). Idempotent: a redelivered event after sealing is a no-op
 * (ADR 0006 clarification 1, NFR-27).
 *
 * <p>Total and seal are one atomic {@code addAndSeal}; a crash before it leaves the publish
 * unsealed, and the redelivery recounts items by publishId (no double count).
 */
public class PublishHandler {

  private final Region region;
  private final TemplateVersionIndex index;
  private final WorkItemStore store;
  private final PublishProgressStore progress;
  private final Clock clock;
  private final int pageSize;
  private final Map<String, Object> locks = new ConcurrentHashMap<>();

  public PublishHandler(
      Region region,
      TemplateVersionIndex index,
      WorkItemStore store,
      PublishProgressStore progress,
      Clock clock,
      int pageSize) {
    if (pageSize <= 0) {
      throw new IllegalArgumentException("pageSize must be > 0");
    }
    this.region = Objects.requireNonNull(region);
    this.index = Objects.requireNonNull(index);
    this.store = Objects.requireNonNull(store);
    this.progress = Objects.requireNonNull(progress);
    this.clock = Objects.requireNonNull(clock);
    this.pageSize = pageSize;
  }

  public void handle(PublishEvent event) {
    String publishId = event.publishId();
    // Serialises concurrent deliveries of the same publish within this worker.
    synchronized (locks.computeIfAbsent(publishId, id -> new Object())) {
      if (progress.totals(publishId).sealed()) {
        return;
      }
      long counted = 0;
      Optional<String> token = Optional.empty();
      do {
        Page<EfRef> page =
            index.findEfsByTemplate(region, event.templateId(), token, pageSize);
        for (EfRef ref : page.items()) {
          WorkKey key = new WorkKey(ref.ef(), event.templateId(), event.version());
          WorkItem item = WorkItem.pending(key, ref.firm(), publishId, clock.instant());
          if (store.createIfAbsent(item) || carriesPublish(key, publishId)) {
            counted++;
          }
        }
        token = page.nextToken();
      } while (token.isPresent());
      progress.addAndSeal(publishId, counted);
    }
  }

  private boolean carriesPublish(WorkKey key, String publishId) {
    return store.find(key).map(i -> i.publishId().equals(publishId)).orElse(false);
  }
}
