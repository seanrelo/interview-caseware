package com.caseware.fanout.summary;

import java.util.List;
import java.util.Optional;

/** Append-only (NFR-15): no update or delete method exists. Only the current pointer moves. */
public interface SummaryStore {
  /** The record the pointer designates; re-verifies its hash, throws {@link SummaryTamperedException}. */
  Optional<SummaryRecord> current(PairKey pair);

  Optional<SummaryRecord> get(PairKey pair, String generationId);

  /** All generations of the pair, oldest first. */
  List<SummaryRecord> history(PairKey pair);

  /** Idempotent by (pair, generationId): the first stored record wins and is returned. Does not move the pointer. */
  SummaryRecord append(SummaryRecord record);

  /** Conditional pointer set; true only if no pointer existed. The record must already be appended. */
  boolean moveCurrentIfAbsent(PairKey pair, String generationId);

  /** Explicit supersede: moves the pointer to an already appended record. Old records stay readable. */
  void supersede(PairKey pair, String newGenerationId);
}
