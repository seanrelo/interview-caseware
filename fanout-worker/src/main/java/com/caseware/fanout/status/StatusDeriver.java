package com.caseware.fanout.status;

import com.caseware.fanout.capture.TemplateVersionRecord;
import com.caseware.fanout.domain.TemplateId;
import com.caseware.fanout.domain.VersionId;
import com.caseware.fanout.graph.EligibleResult;
import com.caseware.fanout.graph.VersionGraph;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Pure status derivation (ADR 0008, 0009; NFR-28: reused by reconciliation). No I/O, no clock, no
 * EF load (NFR-21). Never throws: any failure is UNKNOWN, never UP_TO_DATE (FR-25).
 */
public final class StatusDeriver {

  private StatusDeriver() {}

  public static Derived derive(
      Optional<TemplateVersionRecord> rec,
      VersionGraph graph,
      Set<VersionId> declined,
      Set<VersionId> accepted) {
    Optional<VersionId> shown = Optional.empty();
    try {
      if (rec.isEmpty()) {
        return Derived.unknown(Optional.empty()); // FR-24
      }
      TemplateVersionRecord r = rec.get();
      if (r.baseline().isEmpty()) {
        return Derived.unknown(Optional.empty()); // FR-25
      }
      TemplateId template = r.template();
      // Read-your-writes after an accept: the newer of record baseline and latest accepted.
      VersionId effective = r.baseline().get();
      for (VersionId a : accepted) {
        if (graph.find(template, a).isEmpty()) {
          continue; // bad stored data (unknown version) must not poison the derive
        }
        if (graph.isNewer(template, a, effective)) {
          effective = a;
        }
      }
      shown = Optional.of(effective);
      Set<VersionId> knownDeclined = new HashSet<>();
      for (VersionId d : declined) {
        if (graph.find(template, d).isPresent()) {
          knownDeclined.add(d);
        }
      }
      EligibleResult result = graph.eligibleVersion(template, effective);
      return switch (result) {
        case EligibleResult.Unknown u -> Derived.unknown(shown); // FR-12
        case EligibleResult.UpToDate u -> new Derived(UpdateStatus.UP_TO_DATE, shown, Optional.empty());
        case EligibleResult.Eligible e ->
            knownDeclined.contains(e.version())
                // FR-28; Q8: no older candidate is offered. Baseline untouched (FR-29).
                ? new Derived(UpdateStatus.UP_TO_DATE, shown, Optional.empty())
                // FR-23, FR-26, FR-30: one update from the baseline to the latest eligible.
                : new Derived(UpdateStatus.UPDATE_PENDING, shown, Optional.of(e.version()));
      };
    } catch (Exception e) {
      return Derived.unknown(shown);
    }
  }
}
