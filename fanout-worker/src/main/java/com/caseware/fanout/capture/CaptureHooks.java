package com.caseware.fanout.capture;

import com.caseware.fanout.capture.CaptureMetrics.Event;
import com.caseware.fanout.domain.EfRef;
import com.caseware.fanout.domain.Region;
import com.caseware.fanout.domain.TemplateId;
import com.caseware.fanout.domain.VersionId;
import com.caseware.fanout.port.FirmFlags;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The hook implementation. It holds no loader and no store: its only collaborators are the firm
 * flag and the outbox (NFR-1: zero loads; NFR-2: one local append).
 *
 * <p>Failure choice (NFR-3): every Exception is caught and the hook returns normally. If the outbox
 * append fails, the message goes to a bounded in-memory fallback queue and is re-appended by
 * {@link #retryFallback()} (called by the scheduler next to the relay). The fallback queue is lost
 * on a process crash and drops the oldest message when full; both are counted
 * (FALLBACK_DROPPED). The loss is bounded by the next open/save of the EF, by backfill and by
 * reconciliation (ADR 0013), never silently read as "no update". A failure of the flag lookup
 * fails closed (no capture, counted HOOK_FAILED) so a flag-off firm is never written (NFR-41).
 */
public final class CaptureHooks implements CaptureHook {

  private static final Logger LOG = LoggerFactory.getLogger(CaptureHooks.class);

  private final FirmFlags flags;
  private final CaptureOutbox outbox;
  private final CaptureMetrics metrics;
  private final Supplier<String> ids;
  private final int fallbackCapacity;
  private final Deque<CaptureMessage> fallback = new ArrayDeque<>();

  public CaptureHooks(
      FirmFlags flags, CaptureOutbox outbox, CaptureMetrics metrics, int fallbackCapacity) {
    this(flags, outbox, metrics, fallbackCapacity, () -> UUID.randomUUID().toString());
  }

  public CaptureHooks(
      FirmFlags flags,
      CaptureOutbox outbox,
      CaptureMetrics metrics,
      int fallbackCapacity,
      Supplier<String> ids) {
    if (fallbackCapacity <= 0) {
      throw new IllegalArgumentException("fallbackCapacity must be > 0");
    }
    this.flags = Objects.requireNonNull(flags);
    this.outbox = Objects.requireNonNull(outbox);
    this.metrics = Objects.requireNonNull(metrics);
    this.ids = Objects.requireNonNull(ids);
    this.fallbackCapacity = fallbackCapacity;
  }

  @Override
  public void onCreate(Region r, EfRef ef, TemplateId t, VersionId v, Instant at) {
    capture(CaptureMessage.Kind.VERSION, CaptureSource.CREATE, r, ef, t, v, at);
  }

  @Override
  public void onOpen(Region r, EfRef ef, TemplateId t, VersionId v, Instant at) {
    capture(CaptureMessage.Kind.VERSION, CaptureSource.OPEN, r, ef, t, v, at);
  }

  @Override
  public void onSave(Region r, EfRef ef, TemplateId t, VersionId v, Instant at) {
    capture(CaptureMessage.Kind.VERSION, CaptureSource.SAVE, r, ef, t, v, at);
  }

  @Override
  public void onAccept(Region r, EfRef ef, TemplateId t, VersionId accepted, Instant decidedAt) {
    capture(CaptureMessage.Kind.BASELINE, CaptureSource.ACCEPT, r, ef, t, accepted, decidedAt);
  }

  /** Re-appends queued captures; stops at the first failure. Returns how many were re-appended. */
  public int retryFallback() {
    int replayed = 0;
    while (true) {
      CaptureMessage m;
      synchronized (fallback) {
        m = fallback.pollFirst();
      }
      if (m == null) {
        return replayed;
      }
      try {
        outbox.append(m);
        metrics.count(Event.FALLBACK_REPLAYED);
        replayed++;
      } catch (Exception e) {
        synchronized (fallback) {
          fallback.addFirst(m);
        }
        return replayed;
      }
    }
  }

  public int fallbackSize() {
    synchronized (fallback) {
      return fallback.size();
    }
  }

  private void capture(
      CaptureMessage.Kind kind,
      CaptureSource source,
      Region region,
      EfRef ef,
      TemplateId template,
      VersionId version,
      Instant at) {
    try {
      if (!flags.enabled(ef.firm())) {
        metrics.count(Event.HOOK_FLAG_OFF);
        return;
      }
      CaptureMessage m =
          new CaptureMessage(ids.get(), kind, region, ef, template, version, at, source, 0);
      try {
        outbox.append(m);
        metrics.count(Event.HOOK_APPENDED);
      } catch (Exception e) {
        metrics.count(Event.HOOK_FAILED);
        LOG.warn("capture outbox append failed, queued in memory: {}", e.toString());
        queueFallback(m);
      }
    } catch (Exception e) {
      metrics.count(Event.HOOK_FAILED);
      LOG.warn("capture hook failed, EF operation unaffected: {}", e.toString());
    }
  }

  private void queueFallback(CaptureMessage m) {
    synchronized (fallback) {
      if (fallback.size() >= fallbackCapacity) {
        fallback.pollFirst();
        metrics.count(Event.FALLBACK_DROPPED);
      }
      fallback.addLast(m);
      metrics.count(Event.FALLBACK_QUEUED);
    }
  }
}
