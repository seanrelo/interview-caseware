# 0003 Backfill strategy and load budget

- Status: Accepted
- Date: 2026-10-30

## Context

Hard constraints (CLAUDE.md): loading an EF takes ~1 min, is owned by another team with limited capacity, and 800,000 EFs x 1 min = 13,333 h is infeasible as a scan on any read or publish path (NFR-21, NFR-22). Existing EFs have no template-version record (ADR 0002); hooks fill it only as EFs are touched. Residency (NFR-35), additive and reversible rollout (NFR-40, NFR-42), and "failure is never recorded as no update" apply.

Scenarios:
- Backfill is enabled. Within 14 days (to agree) every existing EF has a record (NFR-5) while concurrent loads stay at or below the agreed limit (NFR-4, limit to agree).
- A load fails. The EF stays record-less and is retried (FR-7).

## Options considered

1. **Load all EFs at full speed.** Pros: finishes fast. Cons: overloads the owning team's system (NFR-4); rejected.
2. **Hooks only, no backfill.** Pros: zero load. Cons: dormant EFs never get a record; coverage unbounded; NFR-5 fails.
3. **Rate-limited backfill job with configurable concurrency, skipping hook-covered EFs.** Pros: bounded, tunable, shrinks as hooks fill records. Cons: needs an enumeration of EF IDs per region and a limit agreed with another team.

## Decision

Option 3. A regional backfill job enumerates EF IDs that have no record, loads each through the existing EF loading system, and writes the record via the same idempotent upsert as the hooks (ADR 0002). A token/concurrency limiter caps in-flight loads at a configurable value (NFR-4), changeable at runtime without redeploy. Before each load it re-checks the record and skips EFs already covered by a hook (FR-6). On failure it leaves the EF record-less (status unknown, never "up to date") and reschedules with exponential backoff and jitter (FR-7). The job is enabled per region and can be paused at any time; pausing loses nothing. Backfill is the only legitimate EF loader in this design; NFR-22 is scoped to the publish path, and NFR-21 to the read path.

## Tradeoffs and consequences

- Arithmetic: 800,000 min = 13,333 h. 14 days = 336 h. 13,333 / 336 = 39.7, so at least 40 concurrent loads at 100% utilisation. Plan ~50: 800,000 / 50 = 16,000 min = 267 h = 11.1 days, leaving about 20% headroom for retries and pauses. At exactly 40: 333 h = 13.9 days, no slack.
- The limit is a negotiation with the owning team (to agree). If they grant less than 40, NFR-5 cannot hold: at 20 concurrent, 28 days. Then the target changes, not the limiter. This is the cross-team dependency to settle first.
- Hook-covered EFs shrink the remaining set over time, so the real duration is shorter (active EFs are touched by hooks); not counted here.
- Better: bounded downstream load; coverage metric (NFR-38). Worse: up to 14 days (to agree) during which uncovered EFs show unknown (FR-24), which is the item in the index "requirement to challenge".
- Left out: prioritising EFs by firm or activity (possible later); loading outside business hours (to agree with the owning team).

## Clarification 2026-10-30 (user-approved, no decision changed)

Recorded while writing the spec 01 plan (T1). Status stays as above.

1. NFR-5 is read as "100% attempted, failures counted". Coverage reports attempted, recorded and failed separately (NFR-38). A failing EF stays record-less and shows unknown, never up to date (FR-7, FR-24).
2. Backfill has its own concurrency limit, changeable at runtime. Default 50 planned, 40 minimum for 14 days: 800,000 min / 40 = 13.9 days, / 50 = 11.1 days, / 20 = 28 days. It does not share the fan-out limiter (NFR-4, NFR-8).
3. Q1 stays open: the real capacity of the team that owns EF loading is not known.

## Requirements

FR-6, FR-7, NFR-4, NFR-5, NFR-22 (also NFR-21, NFR-35, NFR-38, NFR-42).
