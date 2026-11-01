package com.caseware.fanout.graph;

import com.caseware.fanout.domain.EfRef;
import com.caseware.fanout.domain.Page;
import com.caseware.fanout.domain.Region;
import com.caseware.fanout.domain.TemplateId;
import com.caseware.fanout.domain.VersionId;
import com.caseware.fanout.domain.WorkKey;
import com.caseware.fanout.port.FirmFlags;
import com.caseware.fanout.port.MarkResult;
import com.caseware.fanout.port.PendingUpdateMarker;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Re-evaluates only the EFs pending on a withdrawn version (FR-13). Inputs are the reverse index,
 * the stored baseline and a fresh graph snapshot; no EF is loaded (NFR-22). The caller has already
 * called {@link VersionRegistry#withdraw} (flag only, FR-10). Writes go through the idempotent
 * monotonic marker path, so duplicate or replayed calls converge (NFR-27, NFR-32).
 *
 * <p>Each EF is written with {@link PendingUpdateMarker#reevaluate}, a compare-and-set on the
 * withdrawn target: it may lower the pending target to an older eligible version, clear it, or set
 * unknown, and never overwrites a newer pending target.
 */
public class WithdrawalReevaluator {

  private final VersionRegistry registry;
  private final PendingTargetIndex index;
  private final BaselineLookup baselines;
  private final PendingUpdateMarker marker;
  private final FirmFlags flags;
  private final int pageSize;

  public WithdrawalReevaluator(
      VersionRegistry registry,
      PendingTargetIndex index,
      BaselineLookup baselines,
      PendingUpdateMarker marker,
      FirmFlags flags,
      int pageSize) {
    if (pageSize <= 0) {
      throw new IllegalArgumentException("pageSize must be > 0");
    }
    this.registry = registry;
    this.index = index;
    this.baselines = baselines;
    this.marker = marker;
    this.flags = flags;
    this.pageSize = pageSize;
  }

  /**
   * @throws IllegalArgumentException if the version is not in the registry (nothing is written)
   * @throws IllegalStateException if the version is not withdrawn
   */
  public ReevaluationReport onWithdrawn(Region region, TemplateId template, VersionId withdrawn) {
    VersionGraph probe = registry.snapshot();
    TemplateVersion node =
        probe
            .find(template, withdrawn)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "unknown version " + withdrawn + " of " + template));
    if (node.status() != VersionStatus.WITHDRAWN) {
      throw new IllegalStateException("version " + withdrawn + " is not withdrawn");
    }

    int evaluated = 0;
    int pending = 0;
    int upToDate = 0;
    int unknown = 0;
    int skipped = 0;
    List<ReevaluationReport.Failure> failed = new ArrayList<>();

    Optional<String> token = Optional.empty();
    do {
      Page<EfRef> page = index.affectedBy(region, template, withdrawn, token, pageSize);
      VersionGraph graph = registry.snapshot(); // fresh, after the withdrawal
      for (EfRef ef : page.items()) {
        evaluated++;
        if (!flags.enabled(ef.firm())) {
          skipped++; // NFR-41: no write; reconciliation picks it up after the flag flips
          continue;
        }
        try {
          switch (evaluate(region, graph, template, withdrawn, ef)) {
            case PENDING -> pending++;
            case UP_TO_DATE -> upToDate++;
            case UNKNOWN -> unknown++;
          }
        } catch (RuntimeException e) {
          failed.add(new ReevaluationReport.Failure(ef, String.valueOf(e)));
          if (markUnknownQuietly(template, withdrawn, ef, "re-evaluation failed: " + e)) {
            unknown++;
          }
        }
      }
      token = page.nextToken();
    } while (token.isPresent());

    return new ReevaluationReport(evaluated, pending, upToDate, unknown, skipped, failed);
  }

  private enum Outcome {
    PENDING,
    UP_TO_DATE,
    UNKNOWN
  }

  private Outcome evaluate(
      Region region, VersionGraph graph, TemplateId template, VersionId withdrawn, EfRef ef) {
    Optional<VersionId> baseline = baselines.baselineOf(region, ef, template);
    if (baseline.isEmpty()) {
      marker.markUnknown(key(ef, template, withdrawn), "no stored baseline");
      return Outcome.UNKNOWN;
    }
    EligibleResult result = graph.eligibleVersion(template, baseline.get());
    // Compare-and-set on the withdrawn target. If it throws, the index entry stays and a replay
    // repairs the EF. UNCHANGED means the stored target is no longer the withdrawn one, so the
    // entry is stale and is dropped as well.
    MarkResult written = marker.reevaluate(key(ef, template, withdrawn), result);
    return switch (result) {
      case EligibleResult.Eligible e -> {
        if (written == MarkResult.APPLIED) {
          index.record(region, template, e.version(), ef); // record before remove: never absent
        } // UNCHANGED: the marker does not hold the eligible target, so do not index it
        index.remove(region, template, withdrawn, ef);
        yield Outcome.PENDING;
      }
      case EligibleResult.Unknown u -> Outcome.UNKNOWN; // entry kept: still needs a decision
      case EligibleResult.UpToDate ignored -> {
        index.remove(region, template, withdrawn, ef);
        yield Outcome.UP_TO_DATE;
      }
    };
  }

  private boolean markUnknownQuietly(
      TemplateId template, VersionId withdrawn, EfRef ef, String reason) {
    try {
      marker.markUnknown(key(ef, template, withdrawn), reason);
      return true;
    } catch (RuntimeException ignored) {
      return false; // stays in failed; the index entry remains so a replay retries it
    }
  }

  private static WorkKey key(EfRef ef, TemplateId template, VersionId version) {
    return new WorkKey(ef.ef(), template, version);
  }
}
