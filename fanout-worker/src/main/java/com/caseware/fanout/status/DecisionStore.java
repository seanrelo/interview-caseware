package com.caseware.fanout.status;

/**
 * Append-only decision records (ADR 0009, NFR-34). There is deliberately no update or delete
 * method. The key is (EF, template, version, type); a duplicate is a no-op and returns
 * {@link DecisionResult#DUPLICATE}. Implementations are thread-safe and live in the regional cell
 * (NFR-35).
 */
public interface DecisionStore extends DecisionReader {
  DecisionResult append(Decision decision);
}
