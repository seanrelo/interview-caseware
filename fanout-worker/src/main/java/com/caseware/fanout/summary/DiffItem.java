package com.caseware.fanout.summary;

import java.util.Objects;

/** One change of the JSON diff. The path is kept in the stored diff only, never in summary text (FR-38). */
public record DiffItem(String id, String path, String sectionName, String change) {
  public DiffItem {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(sectionName, "sectionName");
    Objects.requireNonNull(change, "change");
    if (id.isBlank()) {
      throw new IllegalArgumentException("diff item id must not be blank");
    }
  }
}
