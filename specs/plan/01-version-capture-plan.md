# Plan: 01 Version capture and backfill

## 1. Header

- Spec: [01-version-capture](../archive/01-version-capture.md), version v1 (no version line)
- Date: 2026-10-30
- Depends on: none (T3 needs spec 04 T3, the accept decision)
- Status: Implemented, archived

## 2. Goal and scope

Record each EF's template ID and version without ever loading an EF on a read or publish path: hooks (create, open, save, accept) write through a local outbox to an idempotent regional upsert, and a rate-limited backfill job fills the EFs that hooks have not touched. Out of scope (CLAUDE.md): applying template content to an EF; graph logic (spec 02); fan-out (spec 03); status derivation (spec 04). Scope per Q2: only spec 03 is real code; backend tasks here are "P2" (port plus in-memory reference implementation, only if time allows), the rest is design.

## 3. Requirement coverage

| ID | How satisfied | Task(s) | Test(s) | ADR(s) |
|---|---|---|---|---|
| FR-1 | Create hook reads ID/version from data in memory, writes outbox row, relay and idempotent upsert into `template_version_record` | T2 | TC-1 create captured | 0002 |
| FR-2 | Open hook, same path | T2 | TC-2 open captured | 0002 |
| FR-3 | Save hook, same path | T2 | TC-3 save captured | 0002 |
| FR-4 | Accept writes the baseline field only; conditional on captured-at | T3 (dep spec 04 T3) | TC-4 accept sets baseline, version field untouched | 0002, 0009 |
| FR-5 | One item per EF ID (partition key); conditional write: apply if absent or newer, equal-and-identical is a no-op | T2 | TC-5 duplicate, TC-6 out-of-order keeps newer | 0002 |
| FR-6 | Backfill enumerates record-less EFs, re-checks record before each load, loads via `EfLoader` port, upserts | T4 | TC-7 loads only record-less, TC-8 skips hook-covered | 0003 |
| FR-7 | On load failure EF stays record-less, retry with backoff and jitter | T4 | TC-9 failed load stays record-less and is rescheduled | 0003 |
| NFR-1 | Hook reads in-memory values only; no loader call | T2 | TC-10 loader spy: zero extra loads | 0002 |
| NFR-2 | Hook cost = one local outbox write (target 50 ms p95, to agree) | T2, T5 | TC-11 timing check with in-memory outbox (indicative only) | 0002 |
| NFR-3 | Hook catches all exceptions, EF operation proceeds, capture queued for retry | T2 | TC-12 failing store/outbox, operation still succeeds, capture retried | 0002, 0014 |
| NFR-4 | Concurrency limiter, runtime-configurable; value to agree with owning team (Q1) | T4 | TC-13 in-flight never exceeds limit; TC-14 limit change at runtime | 0003 |
| NFR-5 | Planned concurrency ~50 (800,000 min / 50 = 267 h = 11.1 days); coverage metric; target stated as "100% attempted, failures counted" (proposed, Q6) | T4, T5 | TC-15 load-budget arithmetic; TC-16 coverage metric | 0003 |
| NFR-22 | Backfill is the sole EF loader; hooks and publish path never load | T4, T5 | TC-10, TC-8 | 0003, 0005 |
| NFR-23 | Table keyed by EF ID, 800,000 items; backfill sized for it | T1, T4 | TC-15 | 0001, 0003 |
| NFR-27 | Conditional upsert; duplicates and stale messages no-op | T2 | TC-5, TC-6 | 0002 |
| NFR-28 | Reconciliation reads records, never EFs; record-less EF yields unknown | T1 (design) | review (owned by spec 06 plan) | 0013 |
| NFR-29 | Capture retry queue has a DLQ retained 14 days (to agree) | T1 (design) | design review | 0013 |
| NFR-30 | Outbox is async with timeout and circuit breaker; capture failure isolated | T2 | TC-12 | 0014 |
| NFR-32 | Outbox and queue are durable; relay replays after outage, idempotent | T2 | TC-17 relay restart replays without loss or duplicates | 0002, 0013 |
| NFR-35 | Record table, outbox consumer and backfill run in the firm's region | T1 (design) | design review | 0001 |
| NFR-38 | Backfill coverage metric per region (1 min, to agree) | T4 | TC-16 | 0013 |
| NFR-40 | New table, optional attributes; hooks only add | T1 (design) | design review | 0014 |
| NFR-41 | Hook and backfill are no-ops for a flag-off firm | T2, T4 | TC-18 flag off: no outbox row, no load | 0014 |
| NFR-42 | Flag off stops writes; stored records inert, no migration | T2 | TC-18, TC-19 re-enable resumes | 0014 |
| NFR-43 | Expand then deploy consumers then enable; rolling deploy | T1 (design) | design review | 0014 |

Not applicable (cross-cutting): NFR-21 (no read path here); NFR-24, NFR-25 (no fan-out or large-firm view); NFR-26, NFR-31, NFR-39 (cost, view availability, DLQ alert owned by plans 03 and 06); NFR-33, NFR-34 (no user view or decision record); NFR-36 (nothing global is written); NFR-37 (no inference).

## 4. Architecture decisions

- [0002](../../docs/ADR/0002-template-version-record-and-capture-path.md): outbox plus idempotent conditional upsert, current record only (Proposed).
- [0003](../../docs/ADR/0003-backfill-strategy-and-load-budget.md): rate-limited backfill, ~50 concurrent loads planned.
- Linked: [0001](../../docs/ADR/0001-regional-cells-and-data-ownership.md), [0005](../../docs/ADR/0005-downstream-call-without-ef-load.md), [0009](../../docs/ADR/0009-decision-records-pin-ef-version.md), [0013](../../docs/ADR/0013-reconciliation-observability-recovery.md), [0014](../../docs/ADR/0014-feature-flag-additive-rollout-failure-isolation.md).

## 5. Work breakdown

| Task | Owner | Reqs | Depends | Definition of done |
|---|---|---|---|---|
| T1 | architect | FR-1..5, NFR-23, 28, 29, 35, 40, 43 | none | ADR 0002 and 0003 reviewed; open points Q5, Q6 recorded |
| T2 (P2) | backend | FR-1..3, 5, NFR-1..3, 27, 30, 32, 41, 42 | T1 | `CaptureHook` port, outbox, idempotent conditional upsert keyed by EF ID, flag no-op; TC-1..3, 5, 6, 10..12, 17..19 pass |
| T3 (P2) | backend | FR-4 | T2, spec 04 T3 | accept writes baseline only; TC-4 passes |
| T4 (P2) | backend | FR-6, 7, NFR-4, 5, 22, 23, 38, 41 | T1 (parallel with T2) | backfill behind `EfLoader` port, concurrency limiter, backoff retry, failed EF record-less, coverage metric; TC-7..9, 13..16 pass |
| T5 | qa | all | T2..T4 | traceability table checked; arithmetic test; "not testable" list agreed |

T2 and T4 run in parallel after T1; T3 follows T2.

## 6. Test strategy

- Unit tests with in-memory outbox, record store and `EfLoader` spy (counting loads).
- Failure injection: store throws, relay crash mid-batch, loader fails then succeeds, duplicate and reordered captures (equal final state).
- Arithmetic test: 800,000 / 40 concurrent = 13.9 days; 800,000 / 50 = 11.1 days; 20 concurrent = 28 days (shows NFR-5 fails if less than 40 is granted).
- Not testable here: real 50 ms p95 (NFR-2), real EF-system limit (NFR-4), 14-day coverage (NFR-5), residency (NFR-35), no-maintenance-window (NFR-43): verified by design review and production metrics.

## 7. Risks and open questions

- Riskiest part: the cross-team dependency, the loader limit (Q1). Under 40 concurrent loads NFR-5 cannot hold; the target changes, not the limiter. A second risk is a hook reading the wrong version, which reconciliation cannot detect (ADR 0002).
- Assumptions: index 3 (all "(to agree)" are proposals), index 4, index 8 (unknown for record-less EFs).
- Q1 capacity of the loader and whether backfill and fan-out share one budget (NFR-4 vs NFR-8). Q2 only spec 03 is real code? Q5 current record only or history; do open/save write the actual version or the accepted baseline? Q6 does backfill include inactive EFs; is "100% attempted, failures counted" acceptable for NFR-5?

## 8. Review findings

Proposed spec fixes, held until Q1..Q6 are answered (no spec edited):
- FR-5 is ambiguous (different version for the same EF? retries could overwrite newer): one current record per EF, write only if not older.
- FR-4 vs FR-2/3: state which field each hook writes (version vs baseline).
- NFR-5 "100%" can fail on a corrupt EF because FR-7 retries forever: restate as 100% attempted, failures counted.
- FR-6 "each EF" vs NFR-23 "active EFs": scope inactive EFs (Q6).
- NFR-4 vs NFR-8: one budget or two (Q1).
- NFR-22/NFR-1 vs FR-6: add backfill as the explicit exception (raised in plan 06).
Mockups: none for this spec.

## 9. Changes

- 2026-10-30: first draft.
- 2026-10-30: implemented (T1..T5) in fanout-worker/ (package capture, adapter/memory, config), 327 tests green; archived spec to specs/archive/. User decisions: separate baseline timestamp, NOOP_CONFLICT and NOOP_NO_RECORD as counted no-ops, baseline seeding on create/backfill (and open/save only when creating) with Instant.EPOCH, template change clears and re-seeds, 'attempted, failures counted' for NFR-5, separate backfill limit (default 50), accept without record retried then dead-lettered, flag-off EFs deferred. ADR 0002 clarifications 1-9 and ADR 0003 clarification added (statuses unchanged). Defects D1-D3 found by QA and fixed. Open: Q1 loader capacity; no scheduler for relay/fallback/backfill in the P2 wiring; FR-7 retries forever; ADR 0002 still Proposed.
