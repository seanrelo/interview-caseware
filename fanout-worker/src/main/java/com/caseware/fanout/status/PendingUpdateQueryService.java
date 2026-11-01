package com.caseware.fanout.status;

import com.caseware.fanout.domain.EfId;
import com.caseware.fanout.domain.EfRef;
import com.caseware.fanout.domain.Page;
import com.caseware.fanout.port.FirmFlags;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Reads materialised rows only (ADR 0008, NFR-21): one page plus O(1) totals, never a scan and
 * never an EF load. Flag off returns empty before any read (NFR-41). Rows are read from the
 * caller's firm partition, so a foreign EF cannot be returned (NFR-33). Timer
 * {@code fanout.status.query} and counter {@code fanout.status.query.errors}, tagged by op
 * (NFR-38); the registry is optional.
 */
public final class PendingUpdateQueryService implements PendingUpdateQuery {

  private final StatusStore store;
  private final FirmFlags flags;
  private final EfFirmLookup firms;
  private final MeterRegistry registry;

  public PendingUpdateQueryService(StatusStore store, FirmFlags flags, EfFirmLookup firms) {
    this(store, flags, firms, null);
  }

  public PendingUpdateQueryService(
      StatusStore store, FirmFlags flags, EfFirmLookup firms, MeterRegistry registry) {
    this.store = Objects.requireNonNull(store);
    this.flags = Objects.requireNonNull(flags);
    this.firms = Objects.requireNonNull(firms);
    this.registry = registry;
  }

  @Override
  public Optional<StatusPage> list(Caller caller, Optional<String> token, int size) {
    Objects.requireNonNull(caller, "caller");
    Objects.requireNonNull(token, "token");
    if (size <= 0) {
      throw new IllegalArgumentException("size must be positive");
    }
    return timed(
        "list",
        () -> {
          if (!flags.enabled(caller.firm())) {
            return Optional.empty();
          }
          Page<StatusRow> rows = store.page(caller.firm(), token, size);
          return Optional.of(new StatusPage(rows, store.counts(caller.firm())));
        });
  }

  @Override
  public Optional<StatusRow> detail(Caller caller, EfId ef) {
    Objects.requireNonNull(caller, "caller");
    Objects.requireNonNull(ef, "ef");
    return timed(
        "detail",
        () -> {
          if (!flags.enabled(caller.firm())) {
            return Optional.empty();
          }
          Optional<EfRef> owner = firms.firmOf(ef);
          if (owner.isPresent() && !owner.get().firm().equals(caller.firm())) {
            return Optional.empty();
          }
          return store.find(caller.firm(), ef);
        });
  }

  private <T> T timed(String op, Supplier<T> body) {
    Timer.Sample sample = registry == null ? null : Timer.start(registry);
    String outcome = "ok";
    try {
      return body.get();
    } catch (RuntimeException e) {
      outcome = "error";
      if (registry != null) {
        registry.counter("fanout.status.query.errors", "op", op).increment();
      }
      throw new StatusUnavailableException("status read failed: " + op, e);
    } finally {
      if (sample != null) {
        sample.stop(registry.timer("fanout.status.query", "op", op, "outcome", outcome));
      }
    }
  }
}
