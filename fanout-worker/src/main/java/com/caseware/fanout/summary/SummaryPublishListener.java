package com.caseware.fanout.summary;

import com.caseware.fanout.graph.GraphPublishEvent;
import java.util.Objects;

/** Adapts a publish event to summary pre-generation. Called explicitly; not wired into the fan-out path. */
public class SummaryPublishListener {
  private final SummaryService service;

  public SummaryPublishListener(SummaryService service) {
    this.service = Objects.requireNonNull(service, "service");
  }

  public void onPublish(GraphPublishEvent event) {
    service.onPublished(event);
  }
}
