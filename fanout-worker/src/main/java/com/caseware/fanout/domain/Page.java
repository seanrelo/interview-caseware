package com.caseware.fanout.domain;

import java.util.List;
import java.util.Optional;

public record Page<T>(List<T> items, Optional<String> nextToken) {
  public Page {
    items = List.copyOf(items);
    nextToken = nextToken == null ? Optional.empty() : nextToken;
  }
}
