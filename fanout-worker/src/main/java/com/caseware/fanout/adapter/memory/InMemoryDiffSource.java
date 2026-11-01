package com.caseware.fanout.adapter.memory;

import com.caseware.fanout.summary.Diff;
import com.caseware.fanout.summary.DiffSource;
import com.caseware.fanout.summary.PairKey;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryDiffSource implements DiffSource {
  private final Map<PairKey, Diff> diffs = new ConcurrentHashMap<>();

  public void register(Diff diff) {
    diffs.put(diff.pair(), diff);
  }

  @Override
  public Optional<Diff> diff(PairKey pair) {
    return Optional.ofNullable(diffs.get(pair));
  }
}
