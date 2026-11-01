package com.caseware.fanout.status;

/** Why a row holds its value. DERIVED rows are the output of {@link StatusDeriver}. */
public enum StatusSource {
  DERIVED,
  /** Written when fan-out dead-lettered the EF (FR-19); the next refresh overwrites it. */
  FANOUT_UNKNOWN
}
