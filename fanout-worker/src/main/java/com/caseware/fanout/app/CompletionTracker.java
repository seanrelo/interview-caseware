package com.caseware.fanout.app;

import com.caseware.fanout.domain.ItemState;
import com.caseware.fanout.domain.PublishCounts;
import com.caseware.fanout.domain.PublishStatus;
import com.caseware.fanout.domain.PublishTotals;
import com.caseware.fanout.domain.WorkItem;
import com.caseware.fanout.port.PublishProgressStore;
import com.caseware.fanout.port.WorkItemStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * FR-21 (ADR 0006): publish status derived from the stores. total and sealed come from
 * {@link PublishProgressStore}; done, skipped and dead-lettered are counted from the work items
 * ({@link WorkItemStore#countByPublish}). Nothing is incremented, so a crash between a terminal
 * compare-and-set and any bookkeeping cannot leave a publish incomplete (NFR-10, NFR-12).
 *
 * <p>A publish is complete when sealed and terminal == total, where terminal = done + skipped +
 * dead-lettered. Dead-lettered items are reported separately in {@link PublishStatus}, so a
 * publish with dead letters is complete but not clean (Q4).
 *
 * <p>As a {@link TerminalListener} it only records timer {@code publish.marker.latency} (NFR-38):
 * publishedAt to the successful marker write, for DONE items only. It is a metric, not state.
 */
public class CompletionTracker implements TerminalListener {

  public static final String MARKER_LATENCY = "publish.marker.latency";

  private final PublishProgressStore progress;
  private final WorkItemStore items;
  private final Function<String, Optional<Instant>> publishedAt;
  private final Clock clock;
  private final Timer latency;

  /** {@code publishedAt} maps publishId to the event's publish time (work items do not carry it). */
  public CompletionTracker(
      PublishProgressStore progress,
      WorkItemStore items,
      Function<String, Optional<Instant>> publishedAt,
      Clock clock,
      MeterRegistry registry) {
    this.progress = Objects.requireNonNull(progress);
    this.items = Objects.requireNonNull(items);
    this.publishedAt = Objects.requireNonNull(publishedAt);
    this.clock = Objects.requireNonNull(clock);
    this.latency =
        Timer.builder(MARKER_LATENCY)
            .description("Publish event time to successful pending-update marker write")
            .register(registry);
  }

  @Override
  public void onTerminal(WorkItem item, ItemState terminal) {
    if (terminal == ItemState.DONE) {
      publishedAt
          .apply(item.publishId())
          .ifPresent(
              t -> latency.record(Duration.between(t, clock.instant()).abs()));
    }
  }

  public PublishStatus status(String publishId) {
    PublishTotals t = progress.totals(publishId);
    PublishCounts c = items.countByPublish(publishId);
    return new PublishStatus(
        publishId, t.total(), c.done(), c.skipped(), c.deadLettered(), t.sealed());
  }

  public boolean isComplete(String publishId) {
    return status(publishId).complete();
  }

  /** Complete and no EF left unknown by this publish. */
  public boolean isCompleteAndClean(String publishId) {
    PublishStatus s = status(publishId);
    return s.complete() && s.deadLettered() == 0;
  }
}
