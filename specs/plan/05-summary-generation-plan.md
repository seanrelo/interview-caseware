# Plan: 05 Summary generation

## 1. Header

- Spec: [05-summary-generation](../05-summary-generation.md), version v1 (no version line).
- Date: 2026-10-30. Status: Draft.
- Depends on: [02-template-version-graph](../archive/02-template-version-graph.md) (diff, versions), [04-pending-update-status](../04-pending-update-status.md) (view, "update pending" stays in every summary state). Cross-cutting: [06-cross-cutting](../06-cross-cutting.md), plan [06](06-cross-cutting-plan.md).
- Mockup: [specs/mockups/05-summary-generation](../mockups/05-summary-generation/README.md).

## 2. Goal and scope

Produce one immutable, traceable, AI-labelled summary per (template ID, baseline, target) pair from the JSON diff, share it across all firms, precompute it at publish for baselines in use, and fall back to on-demand generation. Backend tasks are P2 (interface plus in-memory reference implementation, only if time allows; Part 2 real code is spec 03). The UI is Angular, built from the mockup. Out of scope (CLAUDE.md): applying template content to an EF; loading EFs (never, NFR-21/22); sending EF content or identifiers to a model (NFR-37); summarising intermediate versions; per-firm wording.

## 3. Requirement coverage

Test IDs are defined in section 6.

| ID | How satisfied | Task | Test | ADR |
|---|---|---|---|---|
| FR-33 | View requests the summary for (EF baseline, latest eligible) from the global summary read API; cumulative pair, no chaining | T2, T5 | S1, U1 | 0010 |
| FR-34 | Missing pair enqueues the same generation job as publish; view shows "generating"; no timeout stated (Q12) | T2, T5 | S2, U2 | 0010 |
| FR-35 | On publish, take the distinct baselines in use (bare version set exported by regional cells, no EF/firm IDs) that can reach the new version, enqueue one job per pair | T2 | S3 | 0010, 0001 |
| FR-36 | Pair key is firm-independent; single-flight lease (conditional put); stored summary reused, regenerate only if none valid or explicit supersede | T2 | S4, S5 | 0010 |
| FR-37 | Prompt input is the JSON diff only; ModelClient port takes the diff, nothing else | T2 | S6 | 0010, 0011 |
| FR-38 | Validator rejects summary text containing raw JSON paths; stored diff keeps paths | T3 | S7 | 0011 |
| FR-39 | Structured output: statements with `diffItemIds[]` (>=1); UI marker per statement opens its diff item | T3, T5 | S8, U3 | 0011 |
| FR-40 | S3 record holds summary, diff, model ID, prompt version, generation time; index holds hash | T3 | S9 | 0011 |
| FR-41 | Validator rejects a statement with no ID or an ID not in the stored diff; nothing stored | T3 | S10 | 0011 |
| FR-42 | Failure or rejection: no store, retry with backoff scheduled, failure metric; view shows pending plus summary-unavailable | T2, T5 | S11, U4 | 0010, 0011 |
| FR-43 | "AI-generated" label on every summary | T5 | U5 | 0012 |
| NFR-15 | S3 Object Lock compliance; no update API; regeneration writes a new object and moves the pointer | T3 | S12 | 0011 |
| NFR-16 | Retrieve text, diff, model, prompt version, time; Object Lock 7-year retention (to agree) | T3 | S9, design review (retention needs real S3) | 0011 |
| NFR-17 | Config accepts an exact model version ID only; alias pattern rejected at startup; ID stored per record | T2, T4 | S13 | 0012 |
| NFR-18 | Eval harness computes unsupported rate; CI blocks above 1% (to agree) | T4 | E1 | 0012 |
| NFR-19 | Same harness, omission rate; blocks above 5% (to agree) | T4 | E1 | 0012 |
| NFR-20 | Precompute at publish; metric `summary_ready_latency` against 5 min p95 (to agree) | T2, T6 | P1 (timed, mock model) | 0010 |
| NFR-23 (xref) | Work is per distinct pair (40 x B per week), independent of 800,000 EFs | T2 | P1 | 0010 |
| NFR-26 (xref) | Cost formula, X unset (section 7 and plan 06) | T2, T4 | design review (X not given) | 0010, 0012 |
| NFR-27 (xref) | Idempotent jobs: lease plus conditional pointer write; duplicate publish event is a no-op | T2, T3 | S5, S14 | 0010, 0011 |
| NFR-29 (xref) | Failed generation jobs go to DLQ after retry cap, kept 14 days (to agree) | T2 | S11 | 0013 |
| NFR-30 (xref) | Summary failure never blocks the view of pending status or EF operations | T2, T5 | S11, U4 | 0014 |
| NFR-31 (xref) | Summary read API is stateless; availability is the view's SLO | T5 | design review (needs production SLO data) | 0013 |
| NFR-32 (xref) | Durable queue; after outage jobs replay; idempotent | T2 | S14 | 0013 |
| NFR-35 (xref) | Inputs are versions and diffs only; no EF data in this service | T2 | S6 | 0001 |
| NFR-36 (xref) | Global layer stores versions, diffs, summaries; export is bare (template, version) pairs (Q11) | T2, T3 | S6 | 0001 |
| NFR-37 (xref) | ModelClient input type has no EF field; test asserts payload | T2 | S6 | 0010, 0012 |
| NFR-38 (xref) | Summary failure rate and readiness latency metrics | T2 | S11 | 0013 |
| NFR-40 (xref) | New bucket and index table only; no change to existing stores | T3 | design review (infra-level) | 0014 |
| NFR-41 (xref) | Flag off: no summary request, panel hidden | T5 | U6 | 0014 |
| NFR-42 (xref) | Flag off hides display; stored summaries stay inert | T5 | U6 | 0014 |
| NFR-43 (xref) | Additive deploy; consumers tolerate absent summaries | T2 | design review (deploy practice) | 0014 |

Not applicable (reason): NFR-21, 22 (no EF load here), 24, 25 (no fan-out or large-firm read), 28 (reconciles statuses, not summaries), 33, 34 (status and decisions, spec 04).

## 4. Architecture decisions

- [0010](../../docs/ADR/0010-summary-pipeline-key-single-flight-precompute.md): key (template ID, baseline, target), single-flight, precompute plus on-demand.
- [0011](../../docs/ADR/0011-summary-storage-traceability-immutability.md): S3 Object Lock record, DynamoDB pointer, validator.
- [0012](../../docs/ADR/0012-summary-quality-gate-and-model-pinning.md): pinned model, versioned prompt, CI reference-set gate.
- Referenced: [0001](../../docs/ADR/0001-regional-cells-and-data-ownership.md), [0013](../../docs/ADR/0013-reconciliation-observability-recovery.md), [0014](../../docs/ADR/0014-feature-flag-additive-rollout-failure-isolation.md).

## 5. Work breakdown

| Task | Owner | Reqs | Depends | Definition of done |
|---|---|---|---|---|
| T1 | architect | FR-34..36, FR-40, FR-41, NFR-15..19 | none | ADRs 0010, 0011, 0012 accepted (done) |
| T2 (P2) | backend | FR-34..37, FR-42, NFR-17, 20, 27, 29, 30, 32, 35..38 | T1 | `SummaryService`, `ModelClient` port, in-memory single-flight, reuse across firms, retry with backoff; payload has no EF field |
| T3 (P2) | backend | FR-37..41, NFR-15, 16, 27, 36, 40 | T1 | Validator, append-only `SummaryStore` (no update method), record with diff, model, prompt version, time |
| T4 (P2) | backend | NFR-17, 18, 19, 26 | T3 | Eval harness over a labelled set; exit code non-zero above thresholds |
| T5 | frontend | FR-33, 34, 38, 39, 42, 43, NFR-30, 31, 41, 42 | mockup; T2 contract | Summary panel, statement-to-diff links, AI label, generating and unavailable states, flag-off hidden, escaped output, accessible |
| T6 | qa | NFR-20 and all tests below | T2..T5 | Section 6 cases written; NFR-18/19 run via harness |

Parallel: T2, T3 can start together after T1 (shared record type); T5 runs in parallel against a stub API; T4 follows T3; T6 last.

## 6. Test strategy

- S1 reads pair (baseline, latest eligible) across a cumulative gap. S2 on-demand generation for a missing pair. S3 publish enqueues one job per distinct baseline. S4 N concurrent requests give one model call (single-flight). S5 second firm gets the same record ID; replayed job creates no second record. S6 spy on `ModelClient`: payload is the diff only. S7 text with a raw path is rejected. S8 every statement has an ID that resolves. S9 stored record round-trips all fields. S10 statement with no or unknown ID: rejected, not stored. S11 model failure: pending view, unavailable message, retry scheduled, metric incremented, DLQ after cap. S12 no mutation API; regeneration stores a new record and old one stays readable. S13 alias model ID rejected. S14 duplicate and out-of-order publish events, outage replay: equal final state.
- U1..U6 (component tests): ready, generating, unavailable, link opens audit details, AI label, flag-off; output escaped.
- E1: harness on the reference set (below 1% unsupported, below 5% omitted, both to agree).
- P1: timed run with a mock model for the NFR-20 pipeline; real latency depends on the model and cannot be shown here.
- Not testable here: Object Lock retention (needs real S3), NFR-26 (X unset), NFR-31 (production data), bit-identical LLM reruns (not guaranteed, ADR 0012).

## 7. Risks and open questions

- Riskiest part: the validator checks structure, not truth. A summary can cite a real diff item and still misdescribe it; the CI gate is statistical (ADR 0012). Mitigations: AI label, audit details, recommended online sampling (left out).
- Cost: pairs/week = 40 x B (B, distinct baselines in use per product, not given); monthly cost = pairs x (input + output tokens) x unit price. B, tokens and price are unknown, so NFR-26 stays unset (Q14).
- Assumptions (index): 3 (targets to agree), 5 (baseline versions may cross to the global layer), 6 (AI label inferred), 7 (7-year retention).
- Open: Q11 export of bare pairs vs NFR-36; Q12 on-demand timeout or fallback (FR-34); Q13 7-year retention vs erasure requests (Object Lock cannot be shortened); Q14 $X; NFR-18/19 reference set owner and size unspecified.

## 8. Review findings

Spec (wording held until Q1..Q4 are answered; proposed fixes only, no spec edited):
- FR-36 vs NFR-15: "never regenerate" vs "regenerated = new record". Fix: regenerate only when no valid stored summary exists or on explicit supersede.
- FR-35 "baseline in use" undefined and crosses the residency boundary: define as the set of bare (template, version) pairs; only baselines from which the new version is reachable.
- FR-38 applies to summary text, not to the stored diff. FR-41: the link must point to an item in the stored diff. FR-34 has no timeout (Q12). Summary key must include template ID. NFR-18/19 reference set owner unspecified.

Mockup (applied in place by frontend; verified in the mockup README): feature-disabled state with an outside annotation (NFR-41, NFR-42); audit details disclosure with model, prompt version, time (FR-40, NFR-16, NFR-17); statement markers focus the diff item (FR-39); every pair resolves except deliberate failure rows; FR-36 shown within one firm only; rejected and failed look the same (FR-42); IDs tagged and README coverage refreshed. Still not mocked: FR-35, NFR-18..20, generating timeout (Q12); audit-details visibility is TBD.

## 9. Changes

- 2026-10-30: first draft.
- 2026-11-01: Java side implemented (B1..B4, QA) in fanout-worker/ (package summary, summary/eval, adapter/memory, config/SummaryConfiguration), 494 tests green. NOT archived: the Angular UI (mockups in specs/mockups/05-summary-generation) is not built; planned after this spec. User decisions: DiffSource port (no VersionRegistry change), regenerate only if missing or explicit supersede, bare-version BaselinesInUse, small synthetic eval harness. ADR 0010, 0011, 0012 clarifications added (statuses unchanged). QA found and fixed QA-1..6 (orphan records, lost jobs on busy lease, Error handling, path-rule heuristics). Additive port method SummaryJobQueue.defer(job, delay). Open: no DLQ redrive API and no DLQ retention in the in-memory adapter, ensure on a dead-lettered pair returns Failed until supersede/publish replay, ModelId alias check is a deny-list, no scheduler wired, Object Lock/cost X not testable.
- 2026-11-01: minimal Angular UI built (pending-update-ui/, Angular 21, Vitest: 35 tests; ng build ok) with a thin HTTP layer (fanout-worker web package behind fanout.web.enabled, dev-only X-Dev-Firm/X-Dev-User headers, demo profile). Covers list, detail, accept/decline and the summary panel states. NOT archived: deferred by user decision (mockup extras): toolbar simulations, decision log with user/time, server-side filter, versions-in-range count, withdrawn handling, EF names. QA found and fixed QA-W1 (401 before 400), QA-W3/W4 (decision allowed only for the row's pending target, 422 STALE_OR_NOT_PENDING, UNKNOWN rows rejected), QA-U1 (AI-generated marker constant). Not covered: real browser run, 360 px layout, colour contrast, real authentication.
