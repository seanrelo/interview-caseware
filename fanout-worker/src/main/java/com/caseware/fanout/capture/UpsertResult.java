package com.caseware.fanout.capture;

/** Outcome of one conditional write (FR-5, NFR-27). Only APPLIED changes the record. */
public enum UpsertResult {
  APPLIED,
  /** Equal timestamp and identical content. */
  NOOP_DUPLICATE,
  /** Incoming timestamp older than the stored one. */
  NOOP_STALE,
  /** Equal timestamp but different content, or accept for another template. Kept and counted. */
  NOOP_CONFLICT,
  /** Accept for an EF with no record. Nothing is created. */
  NOOP_NO_RECORD
}
