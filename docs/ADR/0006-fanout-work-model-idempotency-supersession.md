# 0006 Fan-out work model, idempotency and supersession

Status: Accepted
Date: 2026-10-30

## Context

Hard constraints (CLAUDE.md): event delivery is unreliable, consumers are idempotent at-least-once, with reconciliation and DLQ; a failure is never "no update"; no EF load per publish (NFR-22); residency (NFR-35, work items live in the regional cell); additive, flagged rollout.

Scenarios: publish events arrive duplicated or out of order, and a worker restarts mid-fan-out (NFR-10, NFR-12, NFR-27). Scale: ~20,000 EFs per publish, ~40 publishes/week, 800,000 items/week at most, 4,000 firms.

## Options considered

A. Process events directly from the queue message, no durable work store. Pros: simple. Cons: queue redelivery is the only dedupe; restarts can lose or repeat work; no per-publish completion.

B. Durable work-item store with a conditional put on (EF, template ID, target version), SQS for dispatch, version-monotonic writes. Pros: exactly one item per key (FR-15); restart safe (NFR-10); completion countable (FR-21). Cons: extra write per EF (~20,000 per publish).

C. Step Functions map per publish. Pros: built-in state. Cons: cost and limits at 20,000 items per execution, poor fit for per-firm throttling.

## Decision

Option B. Work-item key: (EF, template ID, target version), created with a conditional put (attribute_not_exists), so duplicate publish events create nothing new (FR-15, NFR-27). Dispatch via SQS; consumers are idempotent. The status write is version-monotonic: a write sets the pending target only if it is newer in the graph order than the stored one (compare-and-set), so the final state is independent of arrival order (NFR-12, FR-17). A repeated key leaves state unchanged (FR-17).

Supersession (FR-20): at execution time, the worker checks the target against the latest eligible version for the template; if superseded, the item is marked skipped without a downstream call.

Completion (FR-21): a per-publish counter holds total items and a terminal count. Terminal states: done, skipped, dead-lettered. The publish is "fully processed" when terminal = total; dead-lettered items are reported separately, so a publish is never shown as clean when some EFs are unknown (FR-19). Items in flight at restart are redelivered after visibility timeout; completed items are not re-invoked because the store records done (NFR-10).

## Tradeoffs and consequences

- Better: idempotency, order independence, restart safety, observable completion.
- Worse: ~20,000 extra conditional writes per publish; 800,000 per week across 40 publishes (about 1.3 per second averaged over a week, negligible against the burst target); the counter is a hot key (use sharded counters; to agree).
- Left out: cross-publish batching, priority between products.

## Clarification 2026-10-30 (no decision changed)

Recorded while writing the port contracts for spec 03 (plan T1). Status stays Accepted.

1. Publish counter. Each WorkItem stores its `publishId`. The publish total = items created by this publish + items already carrying the same `publishId`. A duplicate or redelivered publish event after the publish is sealed is a no-op: it creates nothing and does not change the total (FR-15, FR-21, NFR-27).
2. Unknown is monotonic too. `PendingUpdateMarker.markUnknown` (FR-19) must never overwrite a newer pending target; it applies only if the stored state is not a pending target newer than the item's target (NFR-12).
3. Graph order for the CAS. `VersionGraphReader` exposes `isNewer(template, candidate, current)` in graph order (ADR 0004); the monotonic compare-and-set uses it, not string or numeric comparison of version IDs.
4. Flag-off firm. An item whose firm flag is off ends `SKIPPED(FLAG_OFF)`: terminal, counted as skipped (FR-21), no marker write, no downstream call (NFR-41; see ADR 0014). Re-enabling the flag relies on reconciliation (ADR 0013), as in ADR 0014.
5. Scope of NFR-10 and marker re-invocation. "No completed item is re-invoked" (NFR-10) holds for items already DONE. A crash between the marker write and `complete(DONE)` leaves the item LEASED; after lease expiry it is re-leased and the marker is called again for the same key (at-least-once). This is safe only because the marker write is a version-monotonic compare-and-set: the second call returns UNCHANGED and state is equal. Consequences: the marker contract must stay idempotent and monotonic, and the lease duration must exceed the worst-case marker call. Otherwise two workers can overlap on one item; the CAS keeps state correct, but the marker is called twice.
6. Recovery from unknown. `PendingUpdateMarker.record` for the same or a newer target than the stored one clears an `unknown` status and sets pending for that target. A successful write recovers the status, so a DLQ replay or a reconciliation re-send of the same (EF, template, target) fixes an EF that was marked unknown (FR-19, NFR-12, NFR-27). Otherwise a monotonic CAS would treat the same target as UNCHANGED and the EF would stay unknown until the next publish. `markUnknown` still never overwrites a newer pending target (item 2), and an older target never clears unknown.
7. Completion is derived from the store, not from a counter. FR-21 completeness = the publish is sealed AND the count of work items for the `publishId` in a terminal state (DONE, SKIPPED, DEAD_LETTERED) equals the publish total. The count is computed by `WorkItemStore` through a count-by-publish operation indexed by `publishId` (in DynamoDB: a GSI on `publishId`, or a per-publish partition). A separate counter can drift when a worker crashes between the terminal compare-and-set and the counter update, so any counter that is kept is only a cache or metric and never decides completeness (FR-21, NFR-10). Tradeoff: one indexed count per completeness check at ~20,000 items per publish; the "hot counter key" concern in the Tradeoffs section no longer applies. Dead-lettered items are still reported separately (Q4), so a publish is not shown as clean when some EFs are unknown (FR-19). This supersedes the per-publish counter wording in the Decision (Completion) paragraph, the hot-key bullet in Tradeoffs, and the "counter" wording in clarification 1: see clarification 7; the publish total and the sealed no-op rule in clarification 1 stand.
8. Supersession also covers ineligible targets. At execution time, an item whose target is not the latest eligible version for its template ends `SKIPPED(SUPERSEDED)` with no marker call and no downstream call. This holds whether the target is older than the latest eligible version, or is withdrawn or otherwise ineligible (FR-11, FR-20; eligibility per ADR 0004, where withdrawn versions are traversable but not selectable). The Decision (Supersession) sentence "if superseded" is read this way: see clarification 8. SKIPPED(SUPERSEDED) is terminal and counts toward completeness (clarification 7).
9. Withdrawn pending target: new marker write (user-approved 2026-10-30; no decision changed). Earlier gap: until now the reverse-index entry for a withdrawn target could be dropped without clearing the EF status, so the EF could keep showing a withdrawn version as pending (FR-13, ADR 0004). `PendingUpdateMarker` gains `reevaluate(WorkKey withdrawnTarget, EligibleResult result)`:
   - It applies only if the stored pending target for (EF, template) is exactly `withdrawnTarget` (compare-and-set on that value).
   - `Eligible(v)` sets pending `v`, even if `v` is older than the withdrawn target. This is the only exception to version monotonicity (clarification 3); it is justified because clarification 8 treats a withdrawn target as ineligible.
   - `UpToDate` clears pending (status: up to date for this template).
   - `Unknown` sets unknown (FR-12, FR-19).
   - If the stored target is anything else (a newer pending target, already moved, or none), it returns UNCHANGED and never overwrites a newer pending target (NFR-12).
   - It is idempotent: a replay of the same withdrawal converges to the same state (NFR-27).
   - It is used by the spec 02 withdrawal re-evaluator (FR-13).
10. Spec 04 owns the status model; this write is the minimal contract spec 04 must honour.
11. Open questions unchanged: Q3 tie-break for "latest" among incomparable versions stays as proposed (publish time desc, then version ID desc); Q4 stays as proposed (dead-lettered counts as terminal, reported separately).

## Requirements

FR-14, FR-15, FR-17, FR-20, FR-21, NFR-10, NFR-12, NFR-27, NFR-32.
