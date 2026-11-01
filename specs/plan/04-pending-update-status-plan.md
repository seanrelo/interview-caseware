# Plan: 04 Pending update status and decisions

## 1. Header

- Spec: [04-pending-update-status](../04-pending-update-status.md), version v1 (no version line). Date 2026-10-30. Status: Draft.
- Depends on: [01-version-capture](../archive/01-version-capture.md) (baseline, records), [02-template-version-graph](../archive/02-template-version-graph.md) (eligible version, FR-11, FR-12, FR-13), [03-publish-fanout](../archive/03-publish-fanout.md) (writes pending status, FR-19 unknown).
- Mockup: [specs/mockups/04-pending-update-status](../mockups/04-pending-update-status/README.md).

## 2. Goal and scope

Show each EF of a user's firm with exactly one status (up to date, update pending, unknown), present accumulated updates as one cumulative update, record accept and decline decisions pinned to (EF, version), and refresh status after each decision. Reads come from a materialised regional status row; no EF is ever loaded. Out of scope (`CLAUDE.md`): applying template content, summary generation (spec 05), real persistence (design only; backend tasks are P2: interface plus in-memory reference only if time allows, Q2). UI is Angular, built from the mockup.

## 3. Requirement coverage

| ID | How satisfied | Tasks | Tests | ADR |
|---|---|---|---|---|
| FR-22 | Status row has one enum value; UI renders badge (text + icon) per row, filter with counts | T2, T4 | U-22 enum exhaustive; UI-22 badge for each status | 0008 |
| FR-23 | `StatusService.derive`: latest eligible newer than baseline and not declined gives pending | T2 | U-23 table of cases | 0008, 0009 |
| FR-24 | No template-version record gives `UNKNOWN` | T2 | U-24 | 0008 |
| FR-25 | Any exception or missing input in derive gives `UNKNOWN`, never `UP_TO_DATE`; UI failed-lookup state shows unknown | T2, T4 | U-25 injected failure; UI-25 | 0008 |
| FR-26 | Candidate is latest eligible, shown from baseline: one cumulative update with version count | T2, T4 | U-26 3 versions accumulate to one | 0009 |
| FR-27 | Append-only `DecisionStore.append(EF, version, DECLINE, user, time)` | T3 | U-27 record fields; duplicate is no-op | 0009 |
| FR-28 | Derive subtracts declined versions of that EF | T2 | U-28 | 0009 |
| FR-29 | Decline never touches baseline | T3 | U-29 baseline equal before/after | 0009 |
| FR-30 | Later version is not in decline set, presented from baseline | T2 | U-30 decline v5, v6/v7 eligible gives pending from baseline | 0009 |
| FR-31 | Accept appends record (user, EF, version, time); baseline set to accepted version via FR-4 as one event (see section 8) | T3, T4 | U-31 | 0009 |
| FR-32 | Decision triggers status row refresh via the same idempotent write path | T3, T4 | I-32 status after decision; UI-32 refreshing state | 0008, 0009 |
| NFR-13 | Fan-out (plan 03) writes the pending row; this plan owns the latency measurement on row availability | T5 | P-13 timed test with in-memory store (10 s p95, to agree) | 0008, 0007 |
| NFR-14 | Firm-keyed partition, paged query, per-firm counter; UI pages | T4, T5 | P-14 40,000 rows first page and total, 1 s p95 (to agree) | 0008 |
| NFR-21 (x) | Read API reads status rows only; no EF-load port in the module | T2 | U-21 no loader dependency | 0008 |
| NFR-23 (x) | 800,000 rows, mean 200/firm, max 40,000 sizing | T5 | P-14 | 0008 |
| NFR-24 (x) | Reads isolated from fan-out writes (separate budget, limiter) | T5 | P-24 reads while writer runs in-memory; real isolation not testable here | 0008, 0007 |
| NFR-25 (x) | Not owned here; fan-out throughput is plan 03 | - | - | 0007 |
| NFR-28 (x) | Derive is the pure function reused by reconciliation | T2 | U-23 | 0013 |
| NFR-30 (x) | Decision and view failures do not block EF operations; UI error state | T3, T4 | UI-30 view error leaves page usable | 0014 |
| NFR-31 (x) | 99.9% monthly (to agree): metric and alarm only | T5 | Not testable here | 0013 |
| NFR-33 (x) | Firm scope taken from authenticated identity, never a client parameter | T3, T4 | U-33 foreign firm ID ignored | 0008 |
| NFR-34 (x) | Append-only records, no update/delete API; retention 7 years (to agree, Q13) | T3 | U-34 no mutation method | 0009 |
| NFR-35 (x) | Status and decisions in the regional cell | T3 | Design review | 0001 |
| NFR-38 (x) | Latency and error metrics on the read API | T5 | U-38 | 0013 |
| NFR-40..43 (x) | Flag off returns no indicator; rows stay inert; UI hides indicator | T2, T4 | U-41 flag off; UI-41 | 0014 |

(x) = cross-cutting NFR of [06-cross-cutting](../06-cross-cutting.md), owned by plan 06.
Not applicable: NFR-22 (no publish path), NFR-36 (no global data), NFR-37 (no model). NFR-26, 27, 29, 32, 39 are owned by plans 03 and 06; duplicate decisions are a no-op (FR-27).

## 4. Architecture decisions

- [0008](../../docs/ADR/0008-pending-status-read-model.md) materialised per-EF status row, firm partition, paged.
- [0009](../../docs/ADR/0009-decision-records-pin-ef-version.md) append-only decision records pin (EF, version) (Proposed, Q8).
- Cross-references: 0001, 0004 (eligible version), 0007, 0013, 0014.

## 5. Work breakdown

| Task | Owner | Reqs | Depends | Definition of done |
|---|---|---|---|---|
| T1 ADRs 0008, 0009 | architect | all | none | Accepted/Proposed; Q8 recorded |
| T2 (P2) `StatusService.derive(record, graph, declines)` pure function | backend | FR-22..26, FR-28, FR-30, NFR-21, NFR-28, NFR-41 | T1, spec 02 graph interface | Table-driven tests green; undeterminable gives `UNKNOWN` |
| T3 (P2) Append-only `DecisionStore`, accept/decline, trigger refresh | backend | FR-27, FR-29, FR-31, FR-32, NFR-30, NFR-33..35 | T2; spec 01 T3 for baseline | Duplicate no-op; decline leaves baseline; refresh invoked once |
| T4 Angular list and detail from mockup: badge text + icon, filter counts, paging, detail with baseline to latest, accept/decline, decision log, loading, error, flag-off, live region for status changes, escaped output | frontend | FR-22..32, NFR-14, NFR-30, NFR-33, NFR-41 | mockup, API contract from T1 | Every state of the mockup matrix reachable; accessibility checks pass |
| T5 Timing and isolation tests, metrics | qa/backend | NFR-13, NFR-14, NFR-24, NFR-31, NFR-38 | T2, T3 | Reports against targets (to agree) |
| T6 Traceability review | qa | all | T2..T5 | Every ID has a test or a stated reason |

Parallel: T2 and T4 (against a stubbed API) run together; T3 after T2; T5/T6 last.

## 6. Test strategy

- Derive table: no record, record and nothing newer, newer eligible, declined latest, declined v5 then v6, branch with no path (FR-12 unknown), withdrawn pending version (FR-13), thrown exception (FR-25).
- Decisions: duplicate accept/decline, decline then accept, concurrent decisions, store failure (surfaces an error, status not changed; user text is Q10).
- Failure injection: status store timeout gives unknown in UI (not up to date); stale row after lost event healed by derive (NFR-28).
- UI (component tests plus axe): each status shown as text and icon; live region announces refresh; list of 40,000 pages without rendering all rows.
- Timed: NFR-13 and NFR-14 with in-memory stores, flagged as indicative only.
- Not testable here: real isolation from fan-out writes (NFR-24), availability (NFR-31), cross-firm access in production IAM (NFR-33 beyond unit level), 7-year retention (NFR-34).

## 7. Risks and open questions

- Riskiest part: status staleness between publish and fan-out write, and the decline semantics edge case (Q8). Assumption 2 (10 s, to agree), 3, 8.
- Q8 latest eligible declined with older undeclined eligible: proposed up to date. Q9 "checking" state for the stale window. Q10 what the user sees if decision recording fails. Q13 retention vs erasure. Q5 whether open/save write the actual version or the accepted baseline (affects FR-4).

## 8. Review findings

Spec (not edited; held until user answers, proposed fixes): FR-26 vs FR-28/FR-30 when latest eligible is declined but an older undeclined eligible exists (Q8); FR-31 and FR-4 both record an accept: define one event with two writes; decision-recording failure behaviour unspecified (Q10); stale window between publish and fan-out (Q9).
Mockup (already fixed in place, verified in the mockup README): flag-off annotation outside the frame; reachability and branch data for FR-11/FR-12; FR-19 dead-lettered state (Larch Foods, plain unknown) and FR-13 withdrawn state (Kiwi Foods) added; mistagged IDs corrected; paging note for large firms; output escaping; coverage table refreshed. Not mocked by decision: decision-recording failure (Q10), NFR-33, NFR-13 timing.

## 9. Changes

- 2026-10-30: initial draft (v1 spec).
- 2026-10-30: Java side implemented (T1..T6) in fanout-worker/ (package status, adapter/memory, config/StatusConfiguration), 385 tests green. NOT archived: the Angular UI (mockups in specs/mockups/04-pending-update-status) is not built; the user chose to build it after spec 05. User decisions: EfFirmLookup port, effective baseline after accept, Q8 declined latest = up to date, decisions rejected while flag off. ADR 0008, 0009, 0014 clarifications added (statuses unchanged; ADR 0009 still Proposed). QA found and fixed Q1 (crafted EfRef decided for a foreign EF) and Q2 (unknown-version accept poisons status). Open: W1 (store down while an UP_TO_DATE row exists), no scheduler/reconciliation wired, refreshFirm scans the region.
- 2026-11-01: minimal Angular UI built (pending-update-ui/, Angular 21, Vitest: 35 tests; ng build ok) with a thin HTTP layer (fanout-worker web package behind fanout.web.enabled, dev-only X-Dev-Firm/X-Dev-User headers, demo profile). Covers list, detail, accept/decline and the summary panel states. NOT archived: deferred by user decision (mockup extras): toolbar simulations, decision log with user/time, server-side filter, versions-in-range count, withdrawn handling, EF names. QA found and fixed QA-W1 (401 before 400), QA-W3/W4 (decision allowed only for the row's pending target, 422 STALE_OR_NOT_PENDING, UNKNOWN rows rejected), QA-U1 (AI-generated marker constant). Not covered: real browser run, 360 px layout, colour contrast, real authentication.
