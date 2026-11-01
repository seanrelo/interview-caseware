package com.caseware.fanout.summary;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Pinned model version identifier. Aliases are rejected (NFR-17). */
public record ModelId(String value) {
  private static final Set<String> ALIASES = Set.of("latest", "default", "stable", "current", "newest");

  public ModelId {
    Objects.requireNonNull(value, "value");
    String v = value.strip().toLowerCase(Locale.ROOT);
    if (v.isEmpty()) {
      throw new IllegalArgumentException("model id must not be blank");
    }
    if (ALIASES.contains(v) || v.endsWith("-latest") || v.endsWith(":latest") || v.endsWith("@latest")) {
      throw new IllegalArgumentException("model id looks like an alias, a pinned version is required: " + value);
    }
  }
}
