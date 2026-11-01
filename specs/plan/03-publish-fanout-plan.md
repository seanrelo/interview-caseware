# Plan: 03 Publish fan-out (Part 2, priority)

## 1. Header

- Spec: [03-publish-fanout](../archive/03-publish-fanout.md), version v1 (no version line). Date 2026-10-30. Status: Implemented, archived.
- Depends on: [02-template-version-graph](../archive/02-template-version-graph.md) (latest eligible version, FR-9, FR-11), [01-version-capture](../archive/01-version-capture.md) (template-version records are the index FR-14 reads).
- Read with: [00-index](../00-index.md), [06-cross-cutting](../06-cross-cutting.md), `CLAUDE.md`.

## 2. Goal and scope

Turn one publish event into exactly one idempotent work item per affected EF and process the items within downstream capacity: retry with backoff, dead-letter to unknown, skip superseded targets, record publish completion. This is the only real code of the exercise: `fanout-worker/`, Java/Spring Boot, in-memory ports, no AWS dependency. Interfaces, contracts and correctness come before completeness. Out of scope (`CLAUDE.md`): applying template content, loading an EF at publish (NFR-22), summaries, the read API, backfill, real DynamoDB/SQS adapters (described in ADRs only). Q2: confirm only spec 03 is real code.

## 3. Requirement coverage

| ID | How satisfied | Tasks | Tests | ADR |
|---|---|---|---|---|
| FR-14 | `TemplateVersionIndex.findEfsByTemplate(region, templateId, pageToken)` paged, per region; reads records, never EFs | T2 | U-14 paging, empty, other template excluded | 0005, 0001 |
| FR-15 | `WorkItemStore.createIfAbsent(key)` conditional put on (EF, templateId, targetVersion); one item per key | T2, T3 | U-15 duplicate publish creates 0 new | 0006 |
| FR-16 | `PendingUpdateMarker.record(...)` port: a write to the status store, no EF load (Q1: owner, latency, capacity of the endpoint) | T3 | U-16 marker called once; spy asserts no EF-load port exists | 0005 |
| FR-17 | Item already `DONE` is not re-invoked; marker write is version-monotonic compare-and-set | T3 | I-17 replayed key leaves state equal | 0006 |
| FR-18 | `RetryPolicy`: exponential backoff, full jitter, 5 attempts (to agree, configurable) | T4 | U-18 attempts and delays with fake clock and seeded random | 0007 |
| FR-19 | After the last attempt: `DeadLetterSink.put`, marker `markUnknown` (reason kept internally); never up to date | T4 | F-19 always-failing downstream ends DLQ + unknown | 0007, 0006 |
| FR-20 | Supersession check at execution time against `VersionGraphReader.latestEligible`; item `SKIPPED`, no call | T6 | U-20 older item skipped, newer processed; race at execute time | 0006 |
| FR-21 | `PublishProgress` counter: total, done, skipped, dead-lettered; complete when terminal = total; dead-lettered reported separately (Q4) | T6 | U-21 complete with and without dead letters | 0006 |
| NFR-8 | `AdaptiveLimiter` global in-flight cap, configured maximum (to agree) | T5 | C-8 in-flight never above cap under load | 0007 |
| NFR-9 | AIMD: halve on throttle signal or error rate >10%/1 min (to agree), additive increase when healthy | T5 | F-9 throttling downstream shrinks limit then recovers | 0007 |
| NFR-10 | Durable `WorkItemStore` states (`PENDING`, `LEASED`, `DONE`, ...) and lease expiry; restart reloads | T3 | F-10 crash mid-batch: nothing lost, no completed item re-invoked | 0006 |
| NFR-11 | Work-conserving per-firm cap 25% (to agree) applied only while other firms have queued work | T5 | F-11 fairness bound under contention; lone firm uses full limit | 0007 |
| NFR-12 | Version-monotonic writes plus idempotent items; final state independent of event order | T3, T6 | P-12 permutations of duplicate/out-of-order publishes, equal final states | 0006 |
| NFR-22 (x) | Worker has no EF-load port; marker write only (backfill is the sole loader) | T3 | U-16 spy | 0005 |
| NFR-23 | Sizing in README: 800,000 items/week, 20,000 per publish; load test with 20,000 items in memory | T7, T8 | L-23 | 0006 |
| NFR-24 (x) | Worker writes rate-limited by limiter, so reads keep their budget (not measurable in-memory) | T5 | Not testable here | 0007, 0008 |
| NFR-25 (x) | Arithmetic: ~2,000 calls/s per publish for 10 s; throughput is capped by NFR-8, so it is a capacity statement (Q1) | T7 | Documented, L-23 throughput report | 0005, 0007 |
| NFR-26 (x) | Cost: ~800,000 conditional writes/week; $X unset (Q14) | T7 | Not testable | 0006 |
| NFR-27 (x) | All consumers idempotent by key | T3 | U-15, I-17, P-12 | 0006 |
| NFR-28 (x) | Same idempotent write path is used by reconciliation (design only here) | T3 | Not tested here | 0013 |
| NFR-29 (x) | DLQ port keeps entries with a retention setting (14 days, to agree) | T4 | U-29 entry retained with timestamp | 0007 |
| NFR-30 (x) | Worker is async; failure only affects the indicator, never EF operations | T3 | F-30 downstream down: worker degrades, no exception to caller | 0014 |
| NFR-32 (x) | Backlog replays at the same limits, no loss | T3, T5 | F-32 outage then drain, all items terminal | 0013, 0007 |
| NFR-35 (x) | One worker instance per region cell; ports take a region; no cross-region call | T2 | U-35 region isolation | 0001 |
| NFR-36 (x) | Publish event carries template ID and version only | T2 | U-36 event type has no EF field | 0001 |
| NFR-38 (x) | Micrometer: backlog, in-flight, limit, DLQ depth, publish-to-marker latency | T5, T6 | U-38 meters registered | 0013 |
| NFR-39 (x) | Gauge `dlq.depth`; alert rule documented (15 min, to agree) | T4, T7 | Not testable here (config only) | 0013 |
| NFR-40..43 (x) | Per-firm flag port checked before marker write; no schema change; stateless worker rolls without window | T3 | U-41 flag off: no marker written | 0014 |

(x) = cross-cutting NFR of [06-cross-cutting](../06-cross-cutting.md), owned by plan 06.
Not applicable: NFR-21 (no read path), NFR-33 (no user access), NFR-34 (no decisions stored), NFR-37 (no model call).

## 4. Architecture decisions

- [0001](../../docs/ADR/0001-regional-cells-and-data-ownership.md) regional cells: worker per region, global event carries no EF data.
- [0005](../../docs/ADR/0005-downstream-call-without-ef-load.md) the FR-16 call is a status-store marker write, no EF load (Proposed, Q1).
- [0006](../../docs/ADR/0006-fanout-work-model-idempotency-supersession.md) durable work items, conditional put, monotonic writes, supersession, completion counter.
- [0007](../../docs/ADR/0007-backpressure-fairness-retry-dlq.md) AIMD limiter, work-conserving fairness, retry, DLQ.
- Cross-references: 0013 reconciliation and observability, 0014 flag and isolation.

## 5. Work breakdown

Layout (names to agree): `fanout-worker/` Maven (or Gradle) module, Java 21, Spring Boot. Packages under `fanout-worker/src/main/java/<base>/`:
- `domain`: `PublishEvent`, `WorkKey`, `WorkItem`, `ItemState`, `FanOutStatus`.
- `port`: `TemplateVersionIndex`, `WorkItemStore`, `PendingUpdateMarker`, `VersionGraphReader`, `PublishProgressStore`, `DeadLetterSink`, `FirmFlags`, `Clock`.
- `app`: `PublishHandler` (FR-14, FR-15), `ItemProcessor` (FR-16..20), `Dispatcher` (leases, limiter), `CompletionTracker` (FR-21).
- `limiter`: `AdaptiveLimiter`, `FirmFairness`, `RetryPolicy`.
- `adapter/memory`: in-memory ports with failure hooks (for tests only, thread-safe).
- `config`: Spring wiring and properties (all "to agree" values).
Tests mirror these packages under `src/test/java`.

| Task | Owner | Reqs | Depends | Definition of done |
|---|---|---|---|---|
| T1 ADRs 0001, 0005, 0006, 0007 (contracts first) | architect | all FR/NFR of spec | none | Done as Proposed/Accepted; port contracts in section 5 agreed |
| T2 Domain and ports, `PublishHandler`: paged index scan, one item per key | backend | FR-14, FR-15, NFR-35, NFR-36 | T1 | Handler creates N items for N matching EFs; duplicate event creates none |
| T3 `ItemProcessor` and in-memory durable store: idempotent marker, lease, restart | backend | FR-16, FR-17, NFR-10, NFR-12, NFR-27, NFR-30, NFR-40..43 | T2 | Marker is monotonic CAS; completed item never re-invoked after simulated restart |
| T4 `RetryPolicy`, DLQ, unknown on exhaustion | backend | FR-18, FR-19, NFR-29, NFR-39 | T3 | 5 attempts (config), jitter, DLQ entry + unknown status |
| T5 `AdaptiveLimiter`, global cap, firm fairness, metrics | backend | NFR-8, NFR-9, NFR-11, NFR-24, NFR-32, NFR-38 | T3 | In-flight never exceeds limit; AIMD reacts; fairness only under contention |
| T6 Supersession and publish completion | backend | FR-20, FR-21, NFR-12, NFR-38 | T3 | Superseded items skipped without call; completion counts dead letters separately |
| T7 README (tradeoffs, arithmetic, how to run), add build/test commands to `CLAUDE.md` | backend | NFR-23, NFR-25, NFR-26, NFR-39 | T2..T6 | README under one page of tradeoffs; `CLAUDE.md` updated |
| T8 Failure-injection and property tests | qa | all, see section 6 | T3..T6 (stubs from T2) | All listed cases green in CI command |

Order: T1, T2, then T3; after T3, T4, T5 and T6 run in parallel (T8 test scaffolding can start after T2 against the ports); T7 last.

## 6. Test strategy

JUnit 5, AssertJ, jqwik for properties; no real clock or sleep (injectable `Clock`, scheduler). In-memory adapters expose failure hooks: `FlakyMarker` (fail N times, throttle, latency), `CrashingStore` (throw after k writes), `SlowIndex`.
- Unit (U): paging, key uniqueness, backoff schedule, supersession compare, completion arithmetic, flag off.
- Failure injection (F): throttling downstream (limit halves, then recovers); error rate above 10% over 1 min; always-failing item (DLQ, unknown, others unaffected); crash mid-batch then restart (no lost item, no completed item repeated, marker call counts exact); outage then drain; concurrent workers on one store (no double processing).
- Property (P): duplicate and out-of-order publish sequences (v5, v7, v5 again, concurrent) yield identical final states (NFR-12); in-flight count never above limit under random latency.
- Fairness (F-11): one firm with 10,000 items plus others queued: its share of in-flight stays at or below 25% (to agree) at every sampled instant; alone, it uses the full limit.
- Load (L): 20,000 items through in-memory ports; report throughput.
- Not testable here: NFR-13 latency against the real downstream (depends on Q1), NFR-24 read isolation, NFR-26 cost, NFR-31 availability, alert delivery (NFR-39), real SQS/DynamoDB semantics (redelivery, visibility timeout), NFR-43. These are covered by design and ADRs.

## 7. Risks and open questions

- Riskiest part: the FR-16 contract (ADR 0005). If the downstream call is really the ~1 min EF load, NFR-22 and NFR-13 cannot hold; the port isolates this and the limiter stays configurable. Assumption 4 (index).
- Other assumptions: 3 (all targets to agree), 8 (FR-12 unknown). Hot counter key at 20,000 items per publish (sharded counter, to agree).
- Open questions: Q1 marker owner/latency/capacity and shared backfill budget; Q2 only spec 03 is real code; Q3 tie-break for "latest" among incomparable versions (proposed publish time desc, then version ID desc); Q4 dead-lettered counts as complete for FR-21 (proposed: terminal, reported separately); Q14 $X unset.

## 8. Review findings

Spec text (not edited; wording held until Q1 to Q4 are answered, proposed fixes):
- FR-16 vs NFR-22: state that the call records a marker and does not load the EF (ADR 0005).
- FR-21: define "complete" as every item terminal (done, skipped, dead-lettered), with dead-lettered reported separately (Q4).
- NFR-11 vs NFR-13: the cap applies only under contention (ADR 0007); a large firm may exceed 10 s then.
- FR-19, FR-24, FR-25: three causes, one user-visible status; keep the reason internally.
- FR-20: "unprocessed" is time-dependent; compare at execution time.
Mockups: none for this spec.

## 9. Changes

- 2026-10-30: initial draft (v1 spec).
- 2026-10-30: implemented (T1..T8) in fanout-worker/, 204 tests green; archived spec to specs/archive/. Decisions recorded as ADR 0006 clarifications 1-9: publishId counter rule, monotonic markUnknown, isNewer, SKIPPED(FLAG_OFF), crash re-invocation, recovery from unknown, completion derived from the store, supersession of ineligible targets. Port changes: PublishProgressStore.addAndSeal and totals, WorkItemStore.find and countByPublish, VersionGraphReader.isNewer and isEligible. Open: Q1 (ADR 0005 Proposed), duplicate DLQ entries on crash, flag-off status relies on read side/reconciliation.
