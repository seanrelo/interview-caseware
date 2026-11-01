# 0002 Template-version record and capture path

- Status: Proposed (current-only vs history is open)
- Date: 2026-10-30

## Context

Hard constraints (CLAUDE.md): loading an EF takes ~1 min and is owned by another team with limited capacity; 800,000 EFs x 1 min = 13,333 h, so never load EFs on a read path or per publish (NFR-21, NFR-22). EF data stays in its region (NFR-35). Delivery is unreliable, consumers are idempotent. Rollout is additive and feature-flagged (NFR-40). Summaries are immutable. A failure is never "no update".

Scenarios:
- Create, open, save or accept happens (FR-1..FR-4). The template ID and version are recorded with zero extra EF loads (NFR-1) and at most 50 ms p95 added (NFR-2, to agree).
- The capture fails. The EF operation completes and the capture is retried (NFR-3, NFR-30).
- The same capture arrives twice or out of order. One entry per EF remains (FR-5, NFR-27).

## Options considered

1. **Synchronous write to the record store inside the hook.** Pros: simple, record is fresh. Cons: store outage or latency hits create/open/save (NFR-2, NFR-3 at risk); the hook needs a distributed write path.
2. **Hook enqueues to a local outbox; async consumer upserts.** Pros: the hook cost is one local write inside the EF system's own transaction/process; failure is isolated and retried; fits at-least-once. Cons: record lags by seconds; needs an outbox relay.
3. **Periodic scan of EFs to derive the record.** Pros: no hooks. Cons: it is the EF load we cannot afford (13,333 h); rejected.

History variant: (a) current record only per EF; (b) append-only history of (EF, version, time).

## Decision

Option 2. Each hook reads the template ID and version from data the EF system already has in memory for that operation (NFR-1), writes an outbox row locally, and returns. A relay publishes to a regional queue; an idempotent consumer upserts into an additive DynamoDB table `template_version_record` in the EF's region, partition key = EF ID, attributes (template ID, version, baseline version, captured-at, source hook). The write is conditional: apply only if the item is absent or the incoming captured-at is newer (or equal with identical content); stale and duplicate messages are no-ops (FR-5, NFR-27). A GSI on template ID serves fan-out lookups (FR-14, see ADR 0005/0006). Any hook exception is caught, logged, the EF operation proceeds, and the capture is queued for retry (NFR-3). Accept (FR-4) writes the baseline field. The hook is a no-op when the firm flag is off (ADR 0014).

History: propose (a) current only. Decisions (accept/decline) and their pinned versions already live in the decision records (ADR 0009), which are the audit trail; the version record is a lookup index. Status stays Proposed until the user confirms no requirement needs per-EF version history.

## Tradeoffs and consequences

- Better: EF operations are insulated (NFR-3, NFR-30); no EF load added (NFR-1); the record is readable without loading the EF.
- Worse: eventual consistency of seconds; an outbox in the EF system is a change to a team we do not own (assumption: they accept it; to agree).
- Arithmetic: fan-out reads the GSI instead of loading EFs. ~20,000 EFs per publish x 40 publishes/week = 800,000 index reads/week against 0 EF loads (loading would be 20,000 min = 333 h per publish).
- Left out: history of versions per EF; cross-region record replication (forbidden by NFR-35).
- Risk: a hook that reads the wrong version writes a wrong record; reconciliation (ADR 0013) cannot detect that without an EF load. Mitigation: hook reads the same field the EF system uses to open the template.

## Clarification 2026-10-30 (user-approved, no decision changed)

Recorded while writing the spec 01 plan (T1). Status stays as above.

1. Two timestamps, two conditional writes. The record has a separate baseline-captured-at attribute next to the version-captured-at. Each has its own conditional write rule: apply if the attribute is absent or the incoming timestamp is newer; equal timestamp and identical content is a no-op (FR-5, NFR-27). So accept and version captures never block each other.
2. Equal timestamp, different content. This is a deterministic counted no-op (`NOOP_CONFLICT`), not an overwrite. The stored value is kept and the conflict is counted for observability (NFR-38).
3. Accept on an EF with no record. Accept (FR-4) on an EF that has no record returns `NOOP_NO_RECORD` and is counted. Nothing is created. The accept stays in the decision record (ADR 0009).
4. Baseline seeding (user decision). Create and backfill write baseline = the captured version only when the baseline is absent. Open and save never write the baseline. Accept writes the baseline only (FR-4) and never the version. The store keeps the current record only, no history; the decision records are the audit trail (ADR 0009). Seed timestamp: see item 7.
5. Open and save seed only on create. Open and save seed the baseline (baseline = the captured version, only when the baseline is absent) only when the write CREATES a new record. When they update an existing record they never touch the baseline. Create and backfill seed whenever the baseline is absent. This is the exact scope of the open/save rule in item 4.
6. Template ID change. A version write whose template ID differs from the stored record (applied because it is newer) replaces templateId and version, and in the same conditional write clears baseline and baselineCapturedAt. The seeding rule then re-seeds if the source is create, backfill, or open/save-creating. `BaselineLookup` returns empty when the requested template ID differs from the record's template ID, so a baseline of another template never reaches the version graph (spec 02). `upsertBaseline` for a template ID different from the record's returns `NOOP_CONFLICT`.
7. Seed timestamp. The seeded baseline carries the lowest possible timestamp (`Instant.EPOCH`), not the version's capture time, so any real accept (FR-4) always wins over a seed. This amends the "version's timestamp" wording of item 4.
8. `NOOP_NO_RECORD` on an accept is retryable in the relay. This amends item 3. In the relay, `NOOP_NO_RECORD` on a BASELINE (accept) message is treated as retryable: the message is nacked with backoff and retried up to the existing max attempts, then dead-lettered (never silently acked or dropped). Reason: an accept can overtake its EF's first version capture when that capture fails transiently and is retried (FR-4, NFR-32). An accept for an EF that never gets a record ends in the capture DLQ (reviewable); the decision record in ADR 0009 keeps the accept regardless.
9. Backfill defers, does not forget. Backfill defers (does not forget) EFs whose firm flag is off and re-checks them on later steps, so re-enabling the flag resumes them without a new enumeration (NFR-41, NFR-42; ADR 0003).

## Requirements

FR-1, FR-2, FR-3, FR-4, FR-5, NFR-1, NFR-2, NFR-3, NFR-40 (also NFR-21, NFR-22, NFR-27, NFR-30, NFR-35).
