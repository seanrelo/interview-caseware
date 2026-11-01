package com.caseware.fanout.summary;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Immutable audit record (FR-40, NFR-15, NFR-16). The hash is verified at construction. */
public record SummaryRecord(
    String generationId,
    PairKey pair,
    List<Statement> statements,
    Diff diff,
    ModelId model,
    PromptVersion prompt,
    Instant generatedAt,
    String contentHash,
    boolean aiGenerated) {

  public SummaryRecord {
    Objects.requireNonNull(generationId, "generationId");
    Objects.requireNonNull(pair, "pair");
    statements = List.copyOf(Objects.requireNonNull(statements, "statements"));
    Objects.requireNonNull(diff, "diff");
    Objects.requireNonNull(model, "model");
    Objects.requireNonNull(prompt, "prompt");
    Objects.requireNonNull(generatedAt, "generatedAt");
    Objects.requireNonNull(contentHash, "contentHash");
    if (generationId.isBlank()) {
      throw new IllegalArgumentException("generationId must not be blank");
    }
    if (statements.isEmpty()) {
      throw new IllegalArgumentException("a summary needs at least one statement");
    }
    if (!diff.pair().equals(pair)) {
      throw new IllegalArgumentException("diff belongs to another pair");
    }
    if (!aiGenerated) {
      throw new IllegalArgumentException("summaries are always AI-generated (FR-43)");
    }
    String expected = hash(generationId, pair, statements, diff, model, prompt, generatedAt, true);
    if (!expected.equals(contentHash)) {
      throw new IllegalArgumentException("content hash mismatch");
    }
  }

  /** Builds a record and computes its hash. */
  public static SummaryRecord create(
      String generationId,
      PairKey pair,
      List<Statement> statements,
      Diff diff,
      ModelId model,
      PromptVersion prompt,
      Instant generatedAt) {
    return new SummaryRecord(
        generationId, pair, statements, diff, model, prompt, generatedAt,
        hash(generationId, pair, List.copyOf(statements), diff, model, prompt, generatedAt, true), true);
  }

  /** Recomputes the hash of the current content. */
  public String recomputeHash() {
    return hash(generationId, pair, statements, diff, model, prompt, generatedAt, aiGenerated);
  }

  private static String hash(
      String generationId, PairKey pair, List<Statement> statements, Diff diff, ModelId model,
      PromptVersion prompt, Instant at, boolean ai) {
    StringBuilder sb = new StringBuilder();
    f(sb, generationId);
    f(sb, pair.template().value());
    f(sb, pair.baseline().value());
    f(sb, pair.target().value());
    f(sb, model.value());
    f(sb, prompt.value());
    f(sb, at.toString());
    f(sb, Boolean.toString(ai));
    f(sb, Integer.toString(diff.items().size()));
    for (DiffItem i : diff.items()) {
      f(sb, i.id());
      f(sb, i.path());
      f(sb, i.sectionName());
      f(sb, i.change());
    }
    f(sb, Integer.toString(statements.size()));
    for (Statement s : statements) {
      f(sb, s.text());
      f(sb, Integer.toString(s.diffItemIds().size()));
      s.diffItemIds().forEach(id -> f(sb, id));
    }
    try {
      byte[] d = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(d);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void f(StringBuilder sb, String v) {
    sb.append(v.length()).append(':').append(v).append(';');
  }
}
