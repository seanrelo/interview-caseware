package com.caseware.fanout.summary;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record Diff(PairKey pair, List<DiffItem> items) {
  public Diff {
    Objects.requireNonNull(pair, "pair");
    items = List.copyOf(Objects.requireNonNull(items, "items"));
    Set<String> seen = new HashSet<>();
    for (DiffItem i : items) {
      if (!seen.add(i.id())) {
        throw new IllegalArgumentException("duplicate diff item id " + i.id());
      }
    }
  }

  public boolean hasItem(String id) {
    return items.stream().anyMatch(i -> i.id().equals(id));
  }
}
