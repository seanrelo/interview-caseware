# Plan: 06 Cross-cutting

## 1. Header

- Spec: [06-cross-cutting](../06-cross-cutting.md), version v1 (no version line).
- Date: 2026-10-30. Status: Draft.
- Depends on: all capability specs ([01](../archive/01-version-capture.md) to [05](../05-summary-generation.md)). This plan owns NFR-21..NFR-43; plans 01..05 list the applicable ones as cross-references.

## 2. Goal and scope

Make the system-wide qualities hold for every capability: no EF load on read or publish paths, scale and cost within bounds, reliability under unreliable events, residency, operability, and additive flagged rollout. Backend tasks (metrics, flag interface) are P2: interface plus in-memory implementation if time allows; the rest is design. Out of scope (CLAUDE.md): applying template content to an EF; cross-region reporting; multi-region failover of one firm.

## 3. Requirement coverage

| ID | Mechanism | Task | Test | ADR |
|---|---|---|---|---|
| NFR-21 | Reads use the status table only; the EF loader port is not wired into any read handler | T1, T4 | X1 (spy: zero loads) | 0008, 0005 |
| NFR-22 | Fan-out calls a marker write (option a), not an EF load. Exception to add (finding): backfill (FR-6) is the sole loader | T1, T4 | X1 | 0005, 0003 |
| NFR-23 | Per-region cells; 800,000 EFs, 4,000 firms, 40 products sized in section 7 | T1 | X2 (arithmetic check) | 0001 |
| NFR-24 | Firm-keyed status partition, separate read budget, fan-out limiter bounds writes | T1 | X3 (timed read during fan-out; load test, not in P1) | 0008, 0007 |
| NFR-25 | Work-conserving limiter; 40 publishes x 20,000 EFs, 10 s => ~2,000 calls/s per publish; feasible only under ADR 0005 | T1, T4 | X2; real-capacity run is design review (downstream capacity unknown, Q1) | 0005, 0007 |
| NFR-26 | Cost formula over distinct summary pairs, storage and writes; X unset (Q14), so a monthly cost metric instead of a limit | T1 | design review: X not given | 0010, 0012 |
| NFR-27 | Idempotent writes keyed by (EF, target) or pair key; duplicate and stale events are no-ops | T4 | X4 (duplicate and reorder) | 0006, 0002, 0013 |
| NFR-28 | Scheduled regional job recomputes status from records, graph and decisions; reads records, not EFs (~9.3 records/s) | T1, T4 | X5 (injected drift is repaired) | 0013 |
| NFR-29 | DLQ retention config 14 days (to agree) | T2 | X6 (config assertion) | 0007, 0013 |
| NFR-30 | Outbox, timeouts, circuit breaker; failing feature leaves EF create, open, save, decide working | T3, T4 | X7 (failure injection in hook and store) | 0014 |
| NFR-31 | SLO 99.9% monthly (to agree); stateless read tier, health metric | T2 | design review: needs production availability data | 0013 |
| NFR-32 | Durable log retention beyond the longest outage; replay from checkpoint | T4 | X8 (stop consumer, publish, resume, compare) | 0013, 0007 |
| NFR-33 | Firm taken from authenticated identity, never client input | T4 | X9 (cross-firm read denied); IAM setup is design review | 0008 |
| NFR-34 | Append-only decision store, retention 7 years (to agree) | T1 | design review: retention needs real storage; Q13 vs erasure | 0009 |
| NFR-35 | Regional cells; metrics and flag stay in-region; no EF data crosses | T1 | X10 (static check: no EF ID in global-layer types) | 0001 |
| NFR-36 | Global layer types limited to version, diff, summary | T1 | X10 | 0001, 0011 |
| NFR-37 | Model client input has no EF field | T4 | X10 (payload spy, see plan 05 S6) | 0010, 0012 |
| NFR-38 | Per-region metrics at 1 min: publish-to-indicator latency, backlog, DLQ depth, backfill coverage, summary failure rate | T2 | X11 (each metric emitted) | 0013 |
| NFR-39 | Alert rule: DLQ depth above 0 for 15 min (to agree) | T2 | X11 (rule evaluates on injected depth) | 0013, 0007 |
| NFR-40 | New tables and nullable attributes only; expand then contract | T1 | design review: infra-level, no schema in P1 | 0014 |
| NFR-41 | Per-firm flag gates hooks, fan-out writes, read API, summary display | T3 | X12 (flag off: empty response) | 0014 |
| NFR-42 | Flag off stops writes and hides reads; data inert | T3 | X12 (off then on, no migration) | 0014 |
| NFR-43 | Rolling or blue/green; consumers tolerate both schema versions | T1 | design review: deployment practice | 0014 |

## 4. Architecture decisions

- [0013](../../docs/ADR/0013-reconciliation-observability-recovery.md): reconciliation, metrics, SLOs, replay.
- [0014](../../docs/ADR/0014-feature-flag-additive-rollout-failure-isolation.md): per-firm flag, additive rollout, failure isolation.
- Referenced: [0001](../../docs/ADR/0001-regional-cells-and-data-ownership.md), [0003](../../docs/ADR/0003-backfill-strategy-and-load-budget.md), [0005](../../docs/ADR/0005-downstream-call-without-ef-load.md), [0007](../../docs/ADR/0007-backpressure-fairness-retry-dlq.md), [0008](../../docs/ADR/0008-pending-status-read-model.md), [0009](../../docs/ADR/0009-decision-records-pin-ef-version.md), [0010](../../docs/ADR/0010-summary-pipeline-key-single-flight-precompute.md), [0011](../../docs/ADR/0011-summary-storage-traceability-immutability.md), [0012](../../docs/ADR/0012-summary-quality-gate-and-model-pinning.md).

## 5. Work breakdown

| Task | Owner | Reqs | Depends | Definition of done |
|---|---|---|---|---|
| T1 | architect | NFR-21..28, 34..37, 40, 43 | none | ADRs 0013, 0014 accepted (done); arithmetic in section 7 agreed |
| T2 (P2) | backend | NFR-29, 31, 38, 39 | T1 | Micrometer metrics (five named, per region, 1 min), alert rule config, DLQ retention config |
| T3 (P2) | backend | NFR-30, 41, 42 | T1 | `FeatureFlags` interface (per firm, in-region, cached with TTL to agree) and in-memory impl; breaker wrapper that no-ops on failure |
| T4 | qa | all NFR-21..43 | T2, T3, spec 03 worker | Cross-cutting matrix executed against the Part 2 worker and stubs; "not testable here" list written |

Parallel: T2 and T3 are independent. T4 reuses spec 03 failure-injection tests where they cover NFR-27, 32.

## 6. Test strategy

- X1 loader spy: zero loads on read and publish paths. X2 arithmetic assertions (section 7). X3 timed read during fan-out. X4 duplicate and out-of-order publishes give equal final state. X5 drift injected, reconciliation repairs, undeterminable gives unknown. X6 DLQ retention config. X7 hook throws or store down: EF operation succeeds. X8 outage replay loses nothing. X9 cross-firm access denied. X10 static and payload checks for residency. X11 metrics emitted, alert fires. X12 flag off and on.
- Not testable here: NFR-26 (X unset), NFR-31 (needs production data), NFR-34 and NFR-43 (infrastructure), NFR-25 against real downstream capacity (unknown, Q1), NFR-33 IAM wiring.

## 7. Risks and open questions

- Scale and cost arithmetic (NFR-26 X unset; only volume is computed):
  - Scanning every EF: 800,000 x 1 min = 13,333 h (~556 days single-threaded; ~14 days at 40 concurrent). Per publish: 20,000 min = 333 h. So no EF load per publish (NFR-22); backfill is the only loader (NFR-4, 5).
  - Fan-out volume: 40 publishes/week x 20,000 EFs = 800,000 work items/week (~6 publishes/day). Under the NFR-13 target of 10 s p95 (to agree), one publish needs 20,000 / 10 s = ~2,000 calls/s; a 25% per-firm cap (NFR-11, to agree) would be 500/s but applies only under contention. Largest firm: 40,000 EFs = 5% of all.
  - Reconciliation: 800,000 records / 86,400 s = ~9.3 records/s, zero EF loads.
  - Summaries: 40 x B pairs/week (B unset); cost = pairs x tokens x price, all unknown, so no dollar figure (Q14).
- Riskiest part: ADR 0005 depends on a marker endpoint owned by another team with unknown latency and capacity (Q1); until agreed the in-flight limit stays configurable and NFR-13/25 are unproven.
- Assumptions (index): 1 (X unset), 3 (targets to agree), 4 (FR-16 marker option a), 7 (7 years).
- Open: Q1 marker owner and shared backfill/fan-out budget; Q13 retention vs erasure (NFR-34, NFR-16); Q14 $X; Q11 export of bare pairs under NFR-36.

## 8. Review findings

Spec wording held until Q1..Q4 are answered; proposed fixes only, no spec edited:
- NFR-22 and NFR-21 vs backfill (FR-6): add the exception that backfill is the sole loader.
- NFR-25: arithmetic only feasible under ADR 0005 (marker, not load): ~2,000 calls/s per publish for 10 s.
- NFR-34: 7-year retention vs erasure requests (Q13).
- NFR-28: state that reconcile reads records, not EFs.
- NFR-23 says "active" EFs; FR-6 backfill scope unclear (Q6).

No mockup exists for spec 06; NFR-33 is not mocked in 04 or 05.

## 9. Changes

- 2026-10-30: first draft.
