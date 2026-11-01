package com.caseware.fanout.adapter.memory;

import com.caseware.fanout.domain.EfId;
import com.caseware.fanout.domain.FirmId;
import com.caseware.fanout.domain.Page;
import com.caseware.fanout.status.StatusCounts;
import com.caseware.fanout.status.StatusRow;
import com.caseware.fanout.status.StatusStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/** Thread-safe: one monitor per firm; rows ordered by EF id; counts adjusted per put in O(1). */
public class InMemoryStatusStore implements StatusStore {

  private static final class FirmRows {
    final NavigableMap<String, StatusRow> rows = new TreeMap<>();
    final long[] counts = new long[3];
  }

  private final Map<FirmId, FirmRows> firms = new ConcurrentHashMap<>();

  @Override
  public void put(StatusRow row) {
    FirmRows f = firms.computeIfAbsent(row.firm(), k -> new FirmRows());
    synchronized (f) {
      StatusRow old = f.rows.put(row.ef().value(), row);
      if (old != null) {
        f.counts[old.status().ordinal()]--;
      }
      f.counts[row.status().ordinal()]++;
    }
  }

  @Override
  public Optional<StatusRow> find(FirmId firm, EfId ef) {
    FirmRows f = firms.get(firm);
    if (f == null) {
      return Optional.empty();
    }
    synchronized (f) {
      return Optional.ofNullable(f.rows.get(ef.value()));
    }
  }

  @Override
  public Page<StatusRow> page(FirmId firm, Optional<String> token, int size) {
    if (size <= 0) {
      throw new IllegalArgumentException("size must be positive");
    }
    FirmRows f = firms.get(firm);
    if (f == null) {
      return new Page<>(List.of(), Optional.empty());
    }
    synchronized (f) {
      NavigableMap<String, StatusRow> rest =
          token.isPresent() ? f.rows.tailMap(token.get(), false) : f.rows;
      List<StatusRow> items = new ArrayList<>(size);
      boolean more = false;
      for (StatusRow r : rest.values()) {
        if (items.size() == size) {
          more = true;
          break;
        }
        items.add(r);
      }
      Optional<String> next =
          more ? Optional.of(items.get(items.size() - 1).ef().value()) : Optional.empty();
      return new Page<>(items, next);
    }
  }

  @Override
  public StatusCounts counts(FirmId firm) {
    FirmRows f = firms.get(firm);
    if (f == null) {
      return new StatusCounts(0, 0, 0);
    }
    synchronized (f) {
      return new StatusCounts(f.counts[0], f.counts[1], f.counts[2]);
    }
  }
}
