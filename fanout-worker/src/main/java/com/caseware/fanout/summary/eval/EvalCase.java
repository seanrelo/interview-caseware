package com.caseware.fanout.summary.eval;

import com.caseware.fanout.summary.Diff;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * One labelled synthetic case (ADR 0012 item 7). The union of the supporting sets is the set of items a
 * statement may cite; expectedItemIds are the items a good summary must mention.
 */
public record EvalCase(
    String name,
    Diff diff,
    Map<String, Set<String>> supportedItemIdsByExpectedStatement,
    Set<String> expectedItemIds) {

  public EvalCase {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(diff, "diff");
    supportedItemIdsByExpectedStatement =
        Map.copyOf(
            Objects.requireNonNull(supportedItemIdsByExpectedStatement, "supported").entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> Set.copyOf(e.getValue()))));
    expectedItemIds = Set.copyOf(Objects.requireNonNull(expectedItemIds, "expected"));
    for (String id : expectedItemIds) {
      if (!diff.hasItem(id)) {
        throw new IllegalArgumentException("case " + name + ": expected item not in diff: " + id);
      }
    }
  }

  public Set<String> allSupportedItemIds() {
    Set<String> all = new HashSet<>();
    supportedItemIdsByExpectedStatement.values().forEach(all::addAll);
    return all;
  }
}
