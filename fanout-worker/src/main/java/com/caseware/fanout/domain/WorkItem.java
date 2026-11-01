package com.caseware.fanout.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Durable work item. {@code leaseUntil}, {@code leaseToken} and {@code skipReason} are null when
 * not applicable. {@code skipReason} is set only for SKIPPED items.
 */
public record WorkItem(
    WorkKey key,
    FirmId firm,
    String publishId,
    ItemState state,
    int attempts,
    Instant notBefore,
    Instant leaseUntil,
    String leaseToken,
    SkipReason skipReason) {

  public WorkItem {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(firm, "firm");
    Objects.requireNonNull(publishId, "publishId");
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(notBefore, "notBefore");
    if (attempts < 0) {
      throw new IllegalArgumentException("attempts must be >= 0");
    }
  }

  public static WorkItem pending(WorkKey key, FirmId firm, String publishId, Instant notBefore) {
    return new WorkItem(key, firm, publishId, ItemState.PENDING, 0, notBefore, null, null, null);
  }

  public WorkItem withSkipReason(SkipReason reason) {
    return new WorkItem(
        key, firm, publishId, state, attempts, notBefore, leaseUntil, leaseToken, reason);
  }
}
