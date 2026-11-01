package com.caseware.fanout.summary;

import java.util.List;

public sealed interface ValidationResult {
  record Valid(List<Statement> statements) implements ValidationResult {
    public Valid {
      statements = List.copyOf(statements);
    }
  }

  record Rejected(List<Violation> violations) implements ValidationResult {
    public Rejected {
      violations = List.copyOf(violations);
    }
  }
}
