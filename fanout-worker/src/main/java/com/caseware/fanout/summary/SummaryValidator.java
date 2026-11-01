package com.caseware.fanout.summary;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Structural validation only (FR-38, FR-39, FR-41): it cannot tell whether a cited item truly supports
 * a statement; that is measured by the evaluation harness (ADR 0012).
 *
 * <p>Raw-path rule (FR-38). Statement text is rejected as RAW_PATH when it contains:
 * <ol>
 *   <li>any stored diff item path string, exact and case-insensitive (path-like paths only);
 *   <li>a {@code $.}-style path, a {@code /a/b} slash path or a {@code [n]} bracket index;
 *   <li>a dotted token (no whitespace, segments {@code [A-Za-z0-9_-]+}, trailing dots ignored) that
 *       is not all-numeric and not on the abbreviation allowlist, when
 *       <ul>
 *         <li>at least one segment is purely numeric ({@code fields.1}, {@code clauses.0.title}), or
 *         <li>it has 3 or more segments ({@code sections.title.text}), or
 *         <li>it has 2 segments that both start with a lowercase letter ({@code clauses.title}).
 *       </ul>
 * </ol>
 * Allowed: version numbers (all segments numeric: {@code 1.2}, {@code 2.0.1}), the allowlist
 * ({@code U.S.A., Ph.D, e.g, i.e, a.m, p.m, ...}), two segments where either starts uppercase or a
 * non-letter (so a missing space after a full stop, {@code ends.The next}, passes), and
 * {@code and/or}. Residual false positives: lowercase sentence joins without a space
 * ({@code done.next}) and unlisted lowercase abbreviations. Residual false negatives: a path whose
 * segments start uppercase ({@code Sections.Title}) and paths hidden by spacing.
 */
public class SummaryValidator {
  private static final Pattern JSON_PATH = Pattern.compile("\\$\\.[A-Za-z_\\[]");
  private static final Pattern SLASH_PATH = Pattern.compile("(?<![\\w/])/[\\w-]+(?:/[\\w\\[\\]-]+)+");
  private static final Pattern BRACKET_INDEX = Pattern.compile("\\[\\d+\\]");
  private static final Pattern DOTTED =
      Pattern.compile("(?<![\\w.\\-])[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)+");
  private static final Set<String> DOTTED_OK =
      Set.of("e.g", "i.e", "u.s", "u.s.a", "u.k", "e.u", "a.m", "p.m", "ph.d", "m.sc", "b.sc", "n.b", "vs");

  public ValidationResult validate(Diff diff, List<ModelStatement> statements) {
    List<Violation> violations = new ArrayList<>();
    if (statements.isEmpty()) {
      violations.add(new Violation(Violation.Kind.NO_STATEMENTS, -1, "summary has no statements"));
    }
    List<Statement> valid = new ArrayList<>();
    for (int i = 0; i < statements.size(); i++) {
      ModelStatement s = statements.get(i);
      int before = violations.size();
      if (s.text().isBlank()) {
        violations.add(new Violation(Violation.Kind.BLANK_TEXT, i, "blank text"));
      }
      if (s.diffItemIds().isEmpty()) {
        violations.add(new Violation(Violation.Kind.NO_DIFF_ITEM, i, "no linked diff item"));
      }
      for (String id : s.diffItemIds()) {
        if (!diff.hasItem(id)) {
          violations.add(new Violation(Violation.Kind.UNKNOWN_DIFF_ITEM, i, id));
        }
      }
      String path = rawPath(diff, s.text());
      if (path != null) {
        violations.add(new Violation(Violation.Kind.RAW_PATH, i, path));
      }
      if (violations.size() == before) {
        valid.add(new Statement(s.text(), s.diffItemIds()));
      }
    }
    return violations.isEmpty() ? new ValidationResult.Valid(valid) : new ValidationResult.Rejected(violations);
  }

  private static String rawPath(Diff diff, String text) {
    for (DiffItem item : diff.items()) {
      String p = item.path();
      boolean pathLike = p.indexOf('.') >= 0 || p.indexOf('/') >= 0 || p.indexOf('[') >= 0 || p.indexOf('$') >= 0;
      if (pathLike && text.contains(p)) {
        return p;
      }
    }
    for (Pattern p : List.of(JSON_PATH, SLASH_PATH, BRACKET_INDEX)) {
      var m = p.matcher(text);
      if (m.find()) {
        return m.group();
      }
    }
    var m = DOTTED.matcher(text);
    while (m.find()) {
      if (looksLikePath(m.group())) {
        return m.group();
      }
    }
    return null;
  }

  private static boolean looksLikePath(String token) {
    if (DOTTED_OK.contains(token.toLowerCase())) {
      return false;
    }
    String[] seg = token.split("\\.");
    int numeric = 0;
    for (String x : seg) {
      if (x.chars().allMatch(Character::isDigit)) {
        numeric++;
      }
    }
    if (numeric == seg.length) {
      return false; // version number
    }
    if (numeric > 0 || seg.length >= 3) {
      return true;
    }
    return Character.isLowerCase(seg[0].charAt(0)) && Character.isLowerCase(seg[1].charAt(0));
  }
}
