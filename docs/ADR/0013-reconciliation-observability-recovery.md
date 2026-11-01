# 0013 Reconciliation, observability and recovery

- Status: Accepted
- Date: 2026-10-30

## Context

Hard constraints (CLAUDE.md): event delivery is unreliable, so consumers are idempotent with periodic reconciliation and a DLQ; a failure is never recorded as "no update". Loading EFs is ~1 min, owned by another team, so reconciliation must never load EFs (NFR-21, NFR-22). EF data stays in-region (NFR-35). 800,000 EFs, 40 products, ~40 publishes/week.

Scenarios:
- An event is lost or reordered. Within 24 h (to agree) every EF's stored status is recomputed and fixed (NFR-28).
- An outage ends. The backlog is replayed with no event lost (NFR-32).
- A work item is dead-lettered. The EF becomes unknown (FR-19) and an alert fires if depth stays above 0 for 15 min (to agree) (NFR-39).
- Operators need to see health. Per-region metrics at 1 min resolution (to agree) (NFR-38).

## Options considered

1. **Trust events only.** Pros: no extra job. Cons: a single lost event leaves a wrong status forever (NFR-28).
2. **Reconcile by reloading EFs.** Pros: ground truth. Cons: 13,333 h; breaks NFR-21/22; rejected.
3. **Reconcile from records, version graph and decision records; replay from durable log.** Pros: no EF load; deterministic; the same pure function as the live path. Cons: cannot detect a wrong record (see ADR 0002), only drift among derived data.

## Decision

Option 3. A regional scheduled job, at least every 24 h (to agree), streams the template-version records, and for each computes eligible version (ADR 0004) against the pinned declines (ADR 0009). It compares the result with the stored status (ADR 0008) and writes corrections through the same idempotent path as fan-out, so reconciliation and live processing converge (NFR-27, NFR-12). A record missing or failing computation yields unknown, never up to date. Publish events and work items sit in durable queues/streams with retention beyond the longest planned outage; after an outage consumers resume from the checkpoint and replay losslessly, safe because processing is idempotent (NFR-32). DLQ retention is at least 14 days (to agree) (NFR-29). Per-region CloudWatch metrics at 1 min: publish-to-indicator latency, fan-out backlog, dead-letter depth, backfill coverage, summary failure rate (NFR-38). SLOs: indicator latency (NFR-13), view availability 99.9% monthly (NFR-31), summary readiness (NFR-20). Alarm: dead-letter depth above 0 for 15 min (NFR-39). Metrics carry no EF identifiers across regions; dashboards aggregate per region and the global view uses counts only (NFR-35).

## Tradeoffs and consequences

- Arithmetic: reconcile reads 800,000 records per 24 h ~ 9.3 records/s average (800,000 / 86,400), versus 0 EF loads; one publish touches ~20,000 EFs, ~40 publishes/week.
- Better: self-healing, bounded staleness 24 h (to agree), auditable. Worse: read capacity cost on the record table, and a window up to 24 h where drift is visible.
- Left out: detecting a wrong record from a hook; cross-region reconciliation.
- Risk: if the graph (ADR 0004) is wrong, reconciliation writes wrong statuses at scale; guard with a dry-run diff count and a threshold that pauses writes (threshold to agree).

## Requirements

NFR-27, NFR-28, NFR-29, NFR-31, NFR-32, NFR-38, NFR-39, FR-19 (also NFR-12, NFR-21, NFR-35).
