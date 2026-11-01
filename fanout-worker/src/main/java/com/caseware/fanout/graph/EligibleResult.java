package com.caseware.fanout.graph;

import com.caseware.fanout.domain.VersionId;

/** Outcome of the eligible-version query. Unknown is never to be read as "no update". */
public sealed interface EligibleResult {
  record Eligible(VersionId version) implements EligibleResult {}

  record UpToDate() implements EligibleResult {}

  record Unknown(String reason) implements EligibleResult {}
}
