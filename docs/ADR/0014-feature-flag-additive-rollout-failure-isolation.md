# 0014 Feature flag, additive rollout and failure isolation

- Status: Accepted
- Date: 2026-10-30

## Context

Hard constraints (CLAUDE.md): rollout has no maintenance window and is additive, feature-flagged and reversible. EFs load in ~1 min under another team's capacity; no EF load on read or publish paths. EF data stays in-region. 4,000 firms, 800,000 EFs.

Scenarios:
- Flag off for a firm. No indicator and no summary for that firm (NFR-41).
- Flag turned off after use. Pre-feature behaviour returns without data migration (NFR-42).
- Any part of the feature fails. EF create, open, save and decisions continue (NFR-30).
- A deploy happens. No maintenance window (NFR-43); only additive changes to existing stores (NFR-40).

## Options considered

1. **Global flag only.** Pros: simple. Cons: all-or-nothing across 4,000 firms; cannot pilot or isolate a firm.
2. **Per-firm flag, but schema changes in place and synchronous calls from the EF system.** Pros: direct. Cons: migration to turn off, and feature failures can block EF operations.
3. **Per-firm flag in the firm's region, additive expand/contract schema, async and time-bounded integration with circuit breaker.** Pros: staged and reversible, failures contained. Cons: flag checks everywhere; more moving parts.

## Decision

Option 3. A per-firm flag, stored and evaluated in the firm's region (NFR-35), gates: hooks (no-op when off, ADR 0002), fan-out writes, the status read API, and the summary display. Read API returns nothing for a flag-off firm (NFR-41). Hooks only add; all new data lives in new tables (the template-version record, status, decisions) and new attributes are optional, so existing readers ignore them (NFR-40). Expand/contract: expand first (new tables, nullable attributes), deploy consumers, enable per firm; contract (remove old paths) only after the flag is permanent and in a later release. Turning the flag off stops writes and hides reads; stored data stays inert, so no migration (NFR-42). Deploys are rolling or blue/green behind consumers that tolerate both schema versions, with no downtime (NFR-43). Failure isolation (NFR-30): the EF system talks to the feature only through the local outbox (async), with hard timeouts, a circuit breaker that opens after repeated failures and short-circuits to no-op, and the capture queued for retry. The feature never holds a lock or transaction the EF operation depends on, and the pending-update view failing leaves the EF screens working.

## Tradeoffs and consequences

- Better: blast radius per firm, safe pilot, instant rollback (flip), EF system isolation. Worse: the flag must be consistent between hooks, API and workers (use one flag source with short cache TTL, to agree); two code paths to test.
- Arithmetic: 4,000 firms enabled in waves; the largest firm (~40,000 EFs, 5% of 800,000) is enabled alone, to observe backfill load (ADR 0003). Re-enabling after off needs records caught up: reconciliation (ADR 0013) repairs the gap within 24 h (to agree).
- Left out: percentage ramp by EF; automated rollback on SLO breach (candidate).
- Risk: a flag-off period misses captures, so records go stale; backfill or reconciliation must catch up before status is trusted. Until then status is unknown.

## Note 2026-10-30

Fan-out detail (no decision changed): a flag-off firm's work item ends `SKIPPED(FLAG_OFF)`, terminal, with no marker write (ADR 0006 clarification).

Note 2026-10-30 (user-approved): clarification, no decision or status change. While a firm's feature flag is off, accept and decline decisions of the pending-update feature are rejected: the feature is fully inert for that firm. The EF itself and its normal save/accept flow in the EF management system are unaffected; only the update-decision feature is gated. Decisions are therefore added to the gated actions (hooks, fan-out writes, status read API, summary display) (FR-27, NFR-41, NFR-42); see ADR 0008 clarification item 4. This is flag-off behaviour, not feature failure, so NFR-30 (decisions continue when the feature fails) is not violated.

## Requirements

NFR-30, NFR-40, NFR-41, NFR-42, NFR-43 (also NFR-35, NFR-21).
