package com.caseware.fanout.adapter.memory;

import com.caseware.fanout.capture.CaptureMetrics;
import com.caseware.fanout.capture.CaptureSource;
import com.caseware.fanout.capture.TemplateVersionRecord;
import com.caseware.fanout.capture.TemplateVersionRecordScan;
import com.caseware.fanout.capture.TemplateVersionRecordStore;
import com.caseware.fanout.capture.UpsertResult;
import com.caseware.fanout.domain.EfRef;
import com.caseware.fanout.domain.Region;
import com.caseware.fanout.domain.TemplateId;
import com.caseware.fanout.domain.VersionId;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Thread-safe in-memory record store. One record per (region, EF id); each write is one atomic
 * compute on that key, and the compute function is pure (it may be re-run under contention), so
 * results are counted after it returns. Counts every {@link UpsertResult} (NFR-38).
 */
public class InMemoryTemplateVersionRecordStore
    implements TemplateVersionRecordStore, TemplateVersionRecordScan {

  private record Outcome(UpsertResult result, TemplateVersionRecord record) {}

  private final Map<Region, ConcurrentSkipListMap<String, TemplateVersionRecord>> byRegion =
      new EnumMap<>(Region.class);
  private final CaptureMetrics metrics;

  public InMemoryTemplateVersionRecordStore() {
    this(new CaptureMetrics());
  }

  public InMemoryTemplateVersionRecordStore(CaptureMetrics metrics) {
    this.metrics = Objects.requireNonNull(metrics);
    for (Region r : Region.values()) {
      byRegion.put(r, new ConcurrentSkipListMap<>());
    }
  }

  public CaptureMetrics metrics() {
    return metrics;
  }

  @Override
  public UpsertResult upsertVersion(
      Region region,
      EfRef ef,
      TemplateId template,
      VersionId version,
      Instant capturedAt,
      CaptureSource source) {
    Objects.requireNonNull(region);
    Objects.requireNonNull(ef);
    Objects.requireNonNull(template);
    Objects.requireNonNull(version);
    Objects.requireNonNull(capturedAt);
    Objects.requireNonNull(source);
    if (source == CaptureSource.ACCEPT) {
      throw new IllegalArgumentException("ACCEPT is not a version capture; use upsertBaseline");
    }
    Outcome[] out = new Outcome[1];
    byRegion
        .get(region)
        .compute(
            ef.ef().value(),
            (k, cur) -> {
              out[0] = versionOutcome(cur, region, ef, template, version, capturedAt, source);
              return out[0].record();
            });
    return done(out[0].result());
  }

  @Override
  public UpsertResult upsertBaseline(
      Region region, EfRef ef, TemplateId template, VersionId accepted, Instant decidedAt) {
    Objects.requireNonNull(region);
    Objects.requireNonNull(ef);
    Objects.requireNonNull(template);
    Objects.requireNonNull(accepted);
    Objects.requireNonNull(decidedAt);
    Outcome[] out = new Outcome[1];
    ConcurrentSkipListMap<String, TemplateVersionRecord> map = byRegion.get(region);
    // computeIfPresent: an absent record is never created by an accept.
    map.computeIfPresent(
        ef.ef().value(),
        (k, cur) -> {
          out[0] = baselineOutcome(cur, template, accepted, decidedAt);
          return out[0].record();
        });
    return done(out[0] == null ? UpsertResult.NOOP_NO_RECORD : out[0].result());
  }

  @Override
  public Optional<TemplateVersionRecord> find(Region region, EfRef ef) {
    return Optional.ofNullable(byRegion.get(region).get(ef.ef().value()));
  }

  @Override
  public Iterable<TemplateVersionRecord> scan(Region region, Optional<String> afterEfId) {
    ConcurrentSkipListMap<String, TemplateVersionRecord> all = byRegion.get(region);
    ConcurrentNavigableMap<String, TemplateVersionRecord> rest =
        afterEfId.isPresent() ? all.tailMap(afterEfId.get(), false) : all;
    return rest.values();
  }

  private UpsertResult done(UpsertResult r) {
    metrics.count(r);
    return r;
  }

  private static Outcome versionOutcome(
      TemplateVersionRecord cur,
      Region region,
      EfRef ef,
      TemplateId template,
      VersionId version,
      Instant at,
      CaptureSource source) {
    if (cur == null) {
      // Creating write seeds the baseline for every source (ADR 0002 clarification 5).
      return new Outcome(
          UpsertResult.APPLIED,
          new TemplateVersionRecord(
              region,
              ef,
              template,
              version,
              Optional.of(version),
              at,
              Optional.of(Instant.EPOCH),
              source));
    }
    boolean sameTemplate = cur.template().equals(template);
    int cmp = at.compareTo(cur.versionCapturedAt());
    if (cmp < 0) {
      return new Outcome(UpsertResult.NOOP_STALE, cur);
    }
    if (cmp == 0) {
      boolean identical = sameTemplate && cur.version().equals(version);
      return new Outcome(identical ? UpsertResult.NOOP_DUPLICATE : UpsertResult.NOOP_CONFLICT, cur);
    }
    // Newer. A different template invalidates the old baseline (it names a version of the old one).
    Optional<VersionId> baseline = sameTemplate ? cur.baseline() : Optional.empty();
    Optional<Instant> baselineAt = sameTemplate ? cur.baselineCapturedAt() : Optional.empty();
    if (baseline.isEmpty() && source.seedsBaseline()) {
      baseline = Optional.of(version);
      baselineAt = Optional.of(Instant.EPOCH);
    }
    return new Outcome(
        UpsertResult.APPLIED,
        new TemplateVersionRecord(
            region, ef, template, version, baseline, at, baselineAt, source));
  }

  private static Outcome baselineOutcome(
      TemplateVersionRecord cur, TemplateId template, VersionId accepted, Instant decidedAt) {
    if (!cur.template().equals(template)) {
      return new Outcome(UpsertResult.NOOP_CONFLICT, cur);
    }
    if (cur.baselineCapturedAt().isPresent()) {
      int cmp = decidedAt.compareTo(cur.baselineCapturedAt().get());
      if (cmp < 0) {
        return new Outcome(UpsertResult.NOOP_STALE, cur);
      }
      if (cmp == 0) {
        boolean identical = cur.baseline().filter(accepted::equals).isPresent();
        return new Outcome(
            identical ? UpsertResult.NOOP_DUPLICATE : UpsertResult.NOOP_CONFLICT, cur);
      }
    }
    return new Outcome(
        UpsertResult.APPLIED,
        new TemplateVersionRecord(
            cur.region(),
            cur.ef(),
            cur.template(),
            cur.version(),
            Optional.of(accepted),
            cur.versionCapturedAt(),
            Optional.of(decidedAt),
            cur.source()));
  }
}
