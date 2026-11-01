package com.caseware.fanout.status;

import com.caseware.fanout.capture.CaptureHook;
import com.caseware.fanout.domain.EfRef;
import com.caseware.fanout.domain.Region;
import com.caseware.fanout.domain.TemplateId;
import com.caseware.fanout.domain.VersionId;
import com.caseware.fanout.port.FirmFlags;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Records accept and decline decisions (FR-27, FR-31) and refreshes the EF's status (FR-32).
 *
 * <p>Order: authorise (firm from the authenticated caller, NFR-33; flag on, NFR-41), append,
 * then for an accept call the capture hook (FR-4: the baseline lands through the outbox; the
 * status already uses the effective baseline), then refresh once. A decline never touches the
 * baseline (FR-29). The append is the commit point: a store failure propagates and nothing else
 * happens. After a successful append nothing thrown by the hook or the refresh escapes (NFR-30):
 * it is counted and reconciliation repairs the row (NFR-28). A duplicate does no hook call and no
 * refresh, so a hook failure on the first call is not retried by a repeated click; the baseline
 * then relies on the next open/save capture and reconciliation (not done here).
 */
public final class DecisionService {

  private final DecisionStore decisions;
  private final StatusService status;
  private final CaptureHook hook;
  private final FirmFlags flags;
  private final Clock clock;
  private final AtomicLong hookFailures = new AtomicLong();
  private final AtomicLong refreshFailures = new AtomicLong();

  public DecisionService(
      DecisionStore decisions,
      StatusService status,
      CaptureHook hook,
      FirmFlags flags,
      Clock clock) {
    this.decisions = Objects.requireNonNull(decisions);
    this.status = Objects.requireNonNull(status);
    this.hook = Objects.requireNonNull(hook);
    this.flags = Objects.requireNonNull(flags);
    this.clock = Objects.requireNonNull(clock);
  }

  /** {@code at} is the decision time; null means now. */
  public DecisionResult accept(
      Region region, Caller caller, EfRef ef, TemplateId template, VersionId version, Instant at) {
    return decide(DecisionType.ACCEPT, region, caller, ef, template, version, at);
  }

  public DecisionResult decline(
      Region region, Caller caller, EfRef ef, TemplateId template, VersionId version, Instant at) {
    return decide(DecisionType.DECLINE, region, caller, ef, template, version, at);
  }

  /** Hook calls that threw after a recorded accept. */
  public long hookFailures() {
    return hookFailures.get();
  }

  /** Refreshes that threw after a recorded decision. */
  public long refreshFailures() {
    return refreshFailures.get();
  }

  private DecisionResult decide(
      DecisionType type,
      Region region,
      Caller caller,
      EfRef ef,
      TemplateId template,
      VersionId version,
      Instant at) {
    Objects.requireNonNull(region, "region");
    Objects.requireNonNull(caller, "caller");
    Objects.requireNonNull(ef, "ef");
    if (!ef.firm().equals(caller.firm())) {
      throw new DecisionRejectedException(DecisionRejectedException.Reason.NOT_YOUR_FIRM);
    }
    // NFR-33: the EfRef comes from client input; the authoritative owner decides. An EF the
    // lookup does not know is never accepted as the caller's.
    if (!status.firmOf(ef.ef()).map(o -> o.firm().equals(caller.firm())).orElse(false)) {
      throw new DecisionRejectedException(DecisionRejectedException.Reason.NOT_YOUR_FIRM);
    }
    if (!flags.enabled(caller.firm())) {
      throw new DecisionRejectedException(DecisionRejectedException.Reason.FEATURE_OFF);
    }
    // FR-25, FR-23: decisions are append-only, so a version the graph does not know must never
    // be stored.
    if (!status.knowsVersion(template, version)) {
      throw new DecisionRejectedException(DecisionRejectedException.Reason.UNKNOWN_VERSION);
    }
    Instant when = at != null ? at : clock.instant();
    DecisionResult result =
        decisions.append(new Decision(ef, template, version, type, caller.user(), when));
    if (result == DecisionResult.DUPLICATE) {
      return result;
    }
    if (type == DecisionType.ACCEPT) {
      try {
        hook.onAccept(region, ef, template, version, when);
      } catch (Exception e) {
        hookFailures.incrementAndGet();
      }
    }
    try {
      status.refresh(region, ef);
    } catch (Exception e) {
      refreshFailures.incrementAndGet();
    }
    return result;
  }
}
