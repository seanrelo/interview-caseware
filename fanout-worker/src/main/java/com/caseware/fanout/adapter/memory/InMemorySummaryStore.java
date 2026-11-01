package com.caseware.fanout.adapter.memory;

import com.caseware.fanout.summary.PairKey;
import com.caseware.fanout.summary.SummaryRecord;
import com.caseware.fanout.summary.SummaryStore;
import com.caseware.fanout.summary.SummaryTamperedException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe, append-only. Like the production index, it keeps the content hash apart from the
 * object and re-verifies it on current().
 */
public class InMemorySummaryStore implements SummaryStore {

  private static final class Slot {
    final Map<String, SummaryRecord> objects = new java.util.LinkedHashMap<>();
    final Map<String, String> indexHash = new java.util.HashMap<>();
    String current;
  }

  private final Map<PairKey, Slot> slots = new ConcurrentHashMap<>();

  private Slot slot(PairKey pair) {
    return slots.computeIfAbsent(pair, k -> new Slot());
  }

  @Override
  public Optional<SummaryRecord> current(PairKey pair) {
    Slot s = slots.get(pair);
    if (s == null) {
      return Optional.empty();
    }
    synchronized (s) {
      if (s.current == null) {
        return Optional.empty();
      }
      SummaryRecord r = s.objects.get(s.current);
      if (!s.indexHash.get(s.current).equals(r.recomputeHash()) || !r.contentHash().equals(r.recomputeHash())) {
        throw new SummaryTamperedException("summary " + s.current + " does not match its recorded hash");
      }
      return Optional.of(r);
    }
  }

  @Override
  public Optional<SummaryRecord> get(PairKey pair, String generationId) {
    Slot s = slots.get(pair);
    if (s == null) {
      return Optional.empty();
    }
    synchronized (s) {
      return Optional.ofNullable(s.objects.get(generationId));
    }
  }

  @Override
  public List<SummaryRecord> history(PairKey pair) {
    Slot s = slots.get(pair);
    if (s == null) {
      return List.of();
    }
    synchronized (s) {
      return List.copyOf(new ArrayList<>(s.objects.values()));
    }
  }

  @Override
  public SummaryRecord append(SummaryRecord record) {
    Slot s = slot(record.pair());
    synchronized (s) {
      SummaryRecord existing = s.objects.putIfAbsent(record.generationId(), record);
      if (existing != null) {
        return existing;
      }
      s.indexHash.put(record.generationId(), record.contentHash());
      return record;
    }
  }

  @Override
  public boolean moveCurrentIfAbsent(PairKey pair, String generationId) {
    Slot s = slot(pair);
    synchronized (s) {
      requireStored(s, generationId);
      if (s.current != null) {
        return false;
      }
      s.current = generationId;
      return true;
    }
  }

  @Override
  public void supersede(PairKey pair, String newGenerationId) {
    Slot s = slot(pair);
    synchronized (s) {
      requireStored(s, newGenerationId);
      s.current = newGenerationId;
    }
  }

  private static void requireStored(Slot s, String generationId) {
    if (!s.objects.containsKey(generationId)) {
      throw new IllegalArgumentException("unknown generation " + generationId);
    }
  }

  /** Test hook simulating storage corruption: swaps the object but leaves the index hash alone. */
  public void tamperForTest(PairKey pair, String generationId, SummaryRecord replacement) {
    Slot s = slot(pair);
    synchronized (s) {
      requireStored(s, generationId);
      s.objects.put(generationId, replacement);
    }
  }
}
