package com.caseware.fanout.summary;

public record Violation(Kind kind, int statementIndex, String detail) {
  public enum Kind {
    NO_STATEMENTS,
    BLANK_TEXT,
    NO_DIFF_ITEM,
    UNKNOWN_DIFF_ITEM,
    RAW_PATH
  }
}
