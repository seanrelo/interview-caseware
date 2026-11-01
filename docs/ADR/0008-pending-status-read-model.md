# 0008 Pending-status read model

Status: Accepted
Date: 2026-10-30

## Context

Hard constraints (CLAUDE.md): no EF load on a read path (NFR-21); residency (NFR-35: status lives in the regional cell); a failure is never "no update" (FR-25); additive, flagged rollout (NFR-40..42); unreliable events, so reconciliation (NFR-28).

Scenarios: a user opens a firm's list; the largest firm has 40,000 EFs; load in 1 s p95 (NFR-14) even while a fan-out is running (NFR-24). Access is limited to the user's firm (NFR-33). Scale: 800,000 EFs, 4,000 firms, mean 200 EFs/firm, median ~40, largest ~40,000.

## Options considered

A. Compute status on read from baseline, graph and decisions. Pros: always fresh. Cons: 40,000 evaluations per request, reads decision tables; fragile at 1 s.

B. Materialised per-EF status row, partition key = firm, paged query. Pros: reads are a single-partition query; fan-out writes are separate rows. Cons: eventual consistency; must reconcile.

C. Shared status table with firm as a global secondary index. Cons: hot partitions and fan-out write interference with reads.

## Decision

Option B. Row: (firm ID [partition], EF ID [sort], status up_to_date | update_pending | unknown, baseline, pending target, template ID, updated at, status source). The query is paged (page size to agree) so a 40,000-EF firm is streamed in pages; the first page is returned quickly and totals come from a per-firm counter. Reads are isolated from fan-out writes by (a) the firm-keyed partition, (b) reading from a separate capacity budget (on-demand with read replicas or a read cache; to agree) and (c) the fan-out limiter (ADR 0007) bounding write rate. Access is scoped by the firm from the authenticated identity, never by a client-supplied firm ID (NFR-33). Anything undeterminable, such as no template-version record, a failed evaluation or no path from baseline, is stored and returned as unknown (FR-24, FR-25, FR-12). Rows are rewritten by fan-out (ADR 0006), decisions (ADR 0009, FR-32) and the 24 h reconciliation (NFR-28, to agree). Reverse index (template, pending target) supports FR-13 (ADR 0004). With the flag off for a firm, the API returns no indicator (NFR-41); rows remain, no migration (NFR-42).

## Tradeoffs and consequences

- Better: NFR-14, NFR-24, NFR-21 by construction; safe defaults.
- Worse: staleness window until write or reconciliation; ~800,000 rows to maintain; two write paths (fan-out and decisions) need version-monotonic writes.
- Arithmetic: 40,000 rows at ~1 KB = ~40 MB per full read; 1 s needs ~40 MB/s on the single partition (to verify), hence paging and a counter.
- Left out: search and filter by arbitrary columns.

## Clarification 2026-10-30 (user-approved, no decision changed)

Recorded for spec 04 (plan T1). Status stays Accepted.

1. Status store and decorator. The status row defined above lives in a new `StatusStore` owned by spec 04. `PendingUpdateMarker` (ADR 0006) stays the fan-out write side and is wrapped by a decorator that, after delegating (`record`, `markUnknown`, `reevaluate`), refreshes the EF's row. The row is always the output of the pure derive over (current record, version graph, decisions) and is the truth; the marker state is only an input hint. `markUnknown` writes UNKNOWN with source `FANOUT_UNKNOWN`, and any later derive overwrites it (FR-19, FR-25, NFR-28).
2. Finding the firm. The firm is found through a new read port `EfFirmLookup` (EfId -> EfRef), fed from the record store. `WorkKey` and `PendingUpdateMarker` are unchanged (NFR-21: no EF load).
3. Effective baseline on accept. Accept uses an effective baseline: the newer of the record baseline and the latest accepted version for the template in the decision store (ADR 0009). The user sees their accept at once, and the row converges when the capture outbox applies the baseline (FR-4, FR-31, FR-32).
4. Listing, catch-up and flag. FR-22 lists rows only. EFs without a row are covered by `StatusService.refreshFirm` as the catch-up primitive (reconciliation, ADR 0013); the flag-off catch-up relies on it (ADR 0014). The query returns empty while the firm flag is off, and decisions are rejected while the firm flag is off, so the feature is fully inert (NFR-41, NFR-42).
5. Failure is never up to date. A failure while deriving or reading is stored or returned as UNKNOWN, or surfaces as a `StatusUnavailableException`, never as up to date (FR-25).

## Requirements

FR-12, FR-22, FR-23, FR-24, FR-25, FR-26, FR-32, NFR-13, NFR-14, NFR-21, NFR-24, NFR-33, NFR-41, NFR-42.
