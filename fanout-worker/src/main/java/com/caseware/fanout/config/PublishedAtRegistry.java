package com.caseware.fanout.config;

import com.caseware.fanout.domain.PublishEvent;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * publishId to publishedAt for the publish-to-marker latency timer (NFR-38). In memory: about 40
 * publishes a week, so it is small, but it is lost on restart and then the timer skips those items.
 */
public class PublishedAtRegistry {

  private final Map<String, Instant> publishedAt = new ConcurrentHashMap<>();

  public void register(PublishEvent event) {
    publishedAt.putIfAbsent(event.publishId(), event.publishedAt());
  }

  public Optional<Instant> lookup(String publishId) {
    return Optional.ofNullable(publishedAt.get(publishId));
  }
}
