# Plan: 02 Template version graph

## 1. Header

- Spec: [02-template-version-graph](../archive/02-template-version-graph.md), version v1 (no version line)
- Date: 2026-10-30
- Depends on: none (T3 uses the status store of spec 04 and the baseline from spec 01)
- Status: Draft

## 2. Goal and scope

Model PT versions as a directed graph (parents, market branch, published or withdrawn) and compute one deterministic eligible version per EF from its baseline, never by loading an EF. Withdrawal sets a flag and re-evaluates only the EFs pending on that version. Out of scope (CLAUDE.md): applying template content; status presentation (spec 04); fan-out mechanics (spec 03). Scope per Q2: only spec 03 is real code; T2 and T3 are "P2" (interface plus in-memory implementation, only if time allows), the rest is design.

## 3. Requirement coverage

| ID | How satisfied | Task(s) | Test(s) | ADR(s) |
|---|---|---|---|---|
| FR-8 | Registry record (template ID, version, parents[], market branch, status); global layer holds no EF data | T1, T2 | TG-1 model round-trip, branch and parents kept | 0004, 0001 |
| FR-9 | Publish event carries template ID and version only; the worker reads the branch and parents from the registry | T1, T2 | TG-2 event payload has exactly two fields | 0004, 0001 |
| FR-10 | Withdrawal sets status to withdrawn; nothing deleted | T2, T3 | TG-3 withdraw flips status, node and edges remain | 0004 |
| FR-11 | `VersionGraph.eligibleVersion(baseline, branch)`: published, non-withdrawn, in branch, reachable from baseline; withdrawn nodes traversable but not selectable (to agree) | T2 | TG-4 branches, TG-5 withdrawn skipped, TG-6 path via withdrawn v6 to v7, TG-7 later v7 containing declined v5 | 0004 |
| FR-12 | Result UNKNOWN only when a later version exists in the branch but is unreachable; nothing newer is UP_TO_DATE (clarification proposed) | T2 | TG-8 unreachable gives UNKNOWN, TG-9 nothing newer gives UP_TO_DATE | 0004 |
| FR-13 | Reverse index (template ID, pending target) to EF keys; re-evaluate only those EFs from graph and stored baseline, no EF load | T3 | TG-10 affected set exact, TG-11 re-evaluation moves EF to next eligible, TG-12 zero loader calls | 0004, 0008 |
| NFR-6 | Pure function of (graph snapshot, baseline, branch); tie-break among incomparable leaves: publish time desc then version ID desc (proposed, Q3) | T2 | TG-13 property test on random DAGs: same input same output, independent of insertion order | 0004 |
| NFR-7 | Withdrawal never deletes content or diffs; retention while any summary references them | T2, T3 | TG-14 withdrawn content and diff still readable | 0004, 0011 |
| NFR-23 | Graph tiny (40 products, tens of versions), cached and evaluated in memory per cell | T2 | TG-15 evaluation of 20,000 EFs in memory | 0004 |
| NFR-27 | Evaluation is pure and re-evaluation writes via the idempotent status path | T2, T3 | TG-16 repeat withdrawal event gives same final state | 0004, 0006 |
| NFR-28 | Reconciliation recomputes eligibility with this function; a stale graph cache is repaired within 24 h (to agree) | T1 (design) | review (owned by plan 06) | 0013 |
| NFR-32 | Withdrawal and publish events replayed from durable log; evaluation deterministic | T3 | TG-16 | 0013 |
| NFR-35 | Graph is global but EF-free; the reverse index lives in the EF's region | T1 (design) | design review | 0001 |
| NFR-36 | Global layer stores only versions, diffs, summaries | T1, T2 | TG-2, design review | 0001 |
| NFR-40 | New registry attributes optional; withdrawal is a flag | T1 (design) | design review | 0014 |
| NFR-41 | Flag-off firm gets no re-evaluation output visible; evaluation itself holds no firm data | T3 | TG-17 flag off: no status written | 0014 |
| NFR-42 | Withdrawn flag and graph are inert data; no migration to switch off | T1 (design) | design review | 0014 |
| NFR-43 | Registry changes expand-only; rolling deploy | T1 (design) | design review | 0014 |

Not applicable (cross-cutting): NFR-21, NFR-22 (the graph holds no EF data, evaluation reads no EF); NFR-24, NFR-25 (no large-firm or multi-publish path here); NFR-26, NFR-29, NFR-31, NFR-38, NFR-39 (cost, DLQ, availability, metrics owned by plans 03 and 06); NFR-30 (no EF-system call); NFR-33, NFR-34 (no user data, no decisions); NFR-37 (no inference).

## 4. Architecture decisions

- [0004](../../docs/ADR/0004-version-graph-and-eligible-version.md): DAG with status flag, reachability query, deterministic tie-break (Proposed).
- Linked: [0001](../../docs/ADR/0001-regional-cells-and-data-ownership.md), [0005](../../docs/ADR/0005-downstream-call-without-ef-load.md) (no-load rule), [0006](../../docs/ADR/0006-fanout-work-model-idempotency-supersession.md), [0008](../../docs/ADR/0008-pending-status-read-model.md), [0011](../../docs/ADR/0011-summary-storage-traceability-immutability.md), [0013](../../docs/ADR/0013-reconciliation-observability-recovery.md), [0014](../../docs/ADR/0014-feature-flag-additive-rollout-failure-isolation.md).

## 5. Work breakdown

| Task | Owner | Reqs | Depends | Definition of done |
|---|---|---|---|---|
| T1 | architect | FR-8..13, NFR-6, 7, 28, 35, 36, 40, 42, 43 | none | ADR 0004 reviewed; Q3 and the withdrawn-traversal assumption recorded |
| T2 (P2) | backend | FR-8..12, NFR-6, 7, 23, 27, 36 | T1 | `VersionGraph` interface and in-memory impl; deterministic `eligibleVersion`; unreachable gives UNKNOWN; TG-1..9, 13..16 pass |
| T3 (P2) | backend | FR-10, 13, NFR-7, 27, 32, 41 | T2, spec 04 status store | withdraw sets status; affected EF set via reverse index; content retained; TG-3, 10..12, 14, 16, 17 pass |
| T4 | qa | all | T2, T3 | traceability checked; random-DAG generator reviewed |

T1 first; T2 and the test design of T4 can run in parallel; T3 after T2.

## 6. Test strategy

- Property tests on random DAGs: determinism under node and edge insertion order, result always published, non-withdrawn, in branch and reachable; no result implies UP_TO_DATE or UNKNOWN per FR-12.
- Case tests: market branches, withdrawn leaf, path through a withdrawn node, later v7 containing declined v5, incomparable leaves (tie-break).
- Failure injection: duplicate and reordered withdrawal events give equal final state; reverse index missing an entry is caught by a reconciliation test (plan 06).
- Not testable here: production-size graph, real registry replication to cells (design review).

## 7. Risks and open questions

- Riskiest part: a wrong graph or a wrong tie-break rule produces wrong statuses at scale, and reconciliation would repair toward the wrong value (ADR 0013 proposes a diff-count guard, threshold to agree).
- Assumptions: index 3, index 8.
- Q3 tie-break among incomparable leaves (proposed publish time desc, then version ID desc). Q7 how withdrawal is communicated (FR-13 trigger). Also from ADR 0004: is a withdrawn version traversable on a path to a later version (assumed yes). Q8 interacts with this (latest eligible declined with older undeclined eligible), owned by plan 04.

## 8. Review findings

Proposed spec fixes, held until Q3 and Q7 are answered (no spec edited):
- NFR-6: state the tie-break among incomparable leaves, otherwise determinism is not testable.
- FR-12 vs "nothing newer": nothing newer is up to date; unknown only when a later version exists in the branch but is unreachable.
- FR-13: no withdraw event is defined (FR-10 sets the flag); name the trigger (Q7).
- FR-9: the event has no branch; the worker reads it from the registry. FR-11: the EF's branch is derived from its version.
Mockups: none for this spec.

## 9. Changes

- 2026-10-30: first draft.
- 2026-10-30: implemented (T1..T4b) in fanout-worker/ (package graph, adapter/memory), 257 tests green; archived spec to specs/archive/. User decisions: total order key (publishedAt, version id) plus registration invariant; withdrawn traversable not selectable; withdrawal via direct method call only (Q7 open); legacy stub stays wired. ADR 0004 clarification (7 items + note), ADR 0006 clarifications 9-10: PendingUpdateMarker.reevaluate (CAS on the withdrawn target; may lower or clear). Open: O-2 string vs numeric version id order; ADR 0004 status still Proposed.
