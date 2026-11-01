package com.caseware.fanout.status;

import com.caseware.fanout.capture.TemplateVersionRecord;
import com.caseware.fanout.capture.TemplateVersionRecordScan;
import com.caseware.fanout.capture.TemplateVersionRecordStore;
import com.caseware.fanout.domain.EfId;
import com.caseware.fanout.domain.EfRef;
import com.caseware.fanout.domain.FirmId;
import com.caseware.fanout.domain.Region;
import com.caseware.fanout.domain.TemplateId;
import com.caseware.fanout.domain.VersionId;
import com.caseware.fanout.port.FirmFlags;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Computes and materialises status rows. The row is the truth (output of {@link StatusDeriver});
 * marker state is only a hint. Refreshes are serialised per EF (striped locks) and read their
 * inputs inside the lock, so a stale refresh cannot overwrite a fresher one. Any failure writes
 * UNKNOWN, never UP_TO_DATE (FR-25). Never loads an EF (NFR-21).
 *
 * <p>Flag off (NFR-41): a refresh writes nothing for the firm; existing rows stay inert (ADR 0008)
 * and the reconciliation after the flag is switched on repairs them.
 */
public final class StatusService {

  private static final int STRIPES = 64;

  private final TemplateVersionRecordStore records;
  private final TemplateVersionRecordScan scan;
  private final VersionGraphProvider graphs;
  private final DecisionReader decisions;
  private final StatusStore store;
  private final FirmFlags flags;
  private final EfFirmLookup firms;
  private final Clock clock;
  private final ReentrantLock[] locks = new ReentrantLock[STRIPES];

  public StatusService(
      TemplateVersionRecordStore records,
      TemplateVersionRecordScan scan,
      VersionGraphProvider graphs,
      DecisionReader decisions,
      StatusStore store,
      FirmFlags flags,
      EfFirmLookup firms,
      Clock clock) {
    this.records = Objects.requireNonNull(records);
    this.scan = Objects.requireNonNull(scan);
    this.graphs = Objects.requireNonNull(graphs);
    this.decisions = Objects.requireNonNull(decisions);
    this.store = Objects.requireNonNull(store);
    this.flags = Objects.requireNonNull(flags);
    this.firms = Objects.requireNonNull(firms);
    this.clock = Objects.requireNonNull(clock);
    for (int i = 0; i < STRIPES; i++) {
      locks[i] = new ReentrantLock();
    }
  }

  /** Resolves the firm of an EF for callers that only hold the EF id. */
  public Optional<EfRef> firmOf(EfId ef) {
    return firms.firmOf(ef);
  }

  /** True when the current graph snapshot knows the version. A graph failure propagates. */
  public boolean knowsVersion(TemplateId template, VersionId version) {
    return graphs.snapshot().find(template, version).isPresent();
  }

  /**
   * Re-derives and writes the row. Flag off: nothing is written and the derived row is returned
   * for the caller only. A failure writes UNKNOWN; if even that write fails the exception
   * propagates (the stale row cannot be repaired here; reconciliation does).
   */
  public StatusRow refresh(Region region, EfRef ef) {
    ReentrantLock lock = lockFor(ef.ef());
    lock.lock();
    try {
      StatusRow row = compute(region, ef);
      if (!flags.enabled(ef.firm())) {
        return row;
      }
      try {
        store.put(row);
        return row;
      } catch (Exception e) {
        StatusRow unknown = unknownRow(ef, row.template(), StatusSource.DERIVED);
        store.put(unknown);
        return unknown;
      }
    } finally {
      lock.unlock();
    }
  }

  /** Writes UNKNOWN with source FANOUT_UNKNOWN (FR-19). Serialised with refreshes. */
  public StatusRow markUnknown(EfRef ef, TemplateId template) {
    ReentrantLock lock = lockFor(ef.ef());
    lock.lock();
    try {
      StatusRow row = unknownRow(ef, Optional.of(template), StatusSource.FANOUT_UNKNOWN);
      if (flags.enabled(ef.firm())) {
        store.put(row);
      }
      return row;
    } finally {
      lock.unlock();
    }
  }

  /**
   * FR-19 with supersession (ADR 0006 clarification 2): the dead-lettered key's target may have
   * been superseded by a newer pending target, in which case the delegate marker ignored the call
   * and the row must keep showing the newer target. Under the EF lock the row is derived; only if
   * it does not show a pending target newer than {@code failedTarget} is UNKNOWN (source
   * FANOUT_UNKNOWN) written. Otherwise the derived row is written (a plain refresh).
   */
  public StatusRow markUnknown(
      Region region, EfRef ef, TemplateId template, VersionId failedTarget) {
    ReentrantLock lock = lockFor(ef.ef());
    lock.lock();
    try {
      StatusRow derived = compute(region, ef);
      boolean superseded = false;
      try {
        superseded =
            derived.status() == UpdateStatus.UPDATE_PENDING
                && graphs
                    .snapshot()
                    .isNewer(template, derived.pendingTarget().orElseThrow(), failedTarget);
      } catch (Exception e) {
        superseded = false; // cannot tell: fail towards UNKNOWN, never towards "no update"
      }
      StatusRow row =
          superseded ? derived : unknownRow(ef, Optional.of(template), StatusSource.FANOUT_UNKNOWN);
      if (flags.enabled(ef.firm())) {
        store.put(row);
      }
      return row;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Reconciliation (NFR-28): refreshes every record of the firm in the region, repairing missing
   * or stale rows. Scans the region (stand-in for a firm index). Returns rows written; 0 when the
   * firm flag is off.
   */
  public int refreshFirm(Region region, FirmId firm) {
    if (!flags.enabled(firm)) {
      return 0;
    }
    int n = 0;
    for (TemplateVersionRecord rec : scan.scan(region, Optional.empty())) {
      if (rec.ef().firm().equals(firm)) {
        refresh(region, rec.ef());
        n++;
      }
    }
    return n;
  }

  private StatusRow compute(Region region, EfRef ef) {
    Optional<TemplateId> template = Optional.empty();
    try {
      Optional<TemplateVersionRecord> rec = records.find(region, ef);
      template = rec.map(TemplateVersionRecord::template);
      Derived d;
      if (rec.isPresent()) {
        TemplateId t = rec.get().template();
        d =
            StatusDeriver.derive(
                rec,
                graphs.snapshot(),
                decisions.declined(ef.ef(), t),
                decisions.accepted(ef.ef(), t));
      } else {
        d = StatusDeriver.derive(rec, null, Set.of(), Set.of());
      }
      return new StatusRow(
          ef.firm(),
          ef.ef(),
          template,
          d.status(),
          d.baseline(),
          d.target(),
          clock.instant(),
          StatusSource.DERIVED);
    } catch (Exception e) {
      return unknownRow(ef, template, StatusSource.DERIVED);
    }
  }

  private StatusRow unknownRow(EfRef ef, Optional<TemplateId> template, StatusSource source) {
    return new StatusRow(
        ef.firm(),
        ef.ef(),
        template,
        UpdateStatus.UNKNOWN,
        Optional.empty(),
        Optional.empty(),
        clock.instant(),
        source);
  }

  private ReentrantLock lockFor(EfId ef) {
    return locks[Math.floorMod(ef.value().hashCode(), STRIPES)];
  }
}
