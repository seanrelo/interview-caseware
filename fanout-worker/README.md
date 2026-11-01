# fanout-worker (Part 2)

Template-publish fan-out worker for one regional cell. A publish event (template ID + version, no EF data) becomes exactly one work item per affected EF, and the items are processed within the capacity of the downstream system. Spec: `specs/archive/03-publish-fanout.md`; plan: `specs/plan/03-publish-fanout-plan.md`; decisions: ADRs 0005, 0006, 0007, 0014.

Short on time? The tradeoffs are in "Tradeoffs", the sizing in "Arithmetic", the open points in "Known gaps and open decisions" (below). Design document: [../docs/02-architecture.md](../docs/02-architecture.md).

## Run

From this directory (Java 21): `./mvnw -q test`. One class: `-Dtest=ClassName`; one method: `-Dtest=ClassName#method`. There is no `main`; the Spring wiring is `config/FanoutConfiguration` and tests drive `Dispatcher.runOnce()`/`drain()` directly (nothing here starts a thread or sleeps). Last run: 528 tests, 0 failures, 0 errors, 0 skipped.

## Shape

- `port/`: `TemplateVersionIndex`, `WorkItemStore`, `PendingUpdateMarker`, `VersionGraphReader`, `PublishProgressStore`, `DeadLetterSink`, `FirmFlags`. `domain/`: keys, items, events.
- `app/`: `PublishHandler` (FR-14/15), `ItemProcessor` (FR-16/17/20), `RetryingFailureHandler` (FR-18/19), `Dispatcher` (lease loop), `CompletionTracker` (FR-21).
- `limiter/`: `AdaptiveLimiter` (NFR-8/9), `FirmFairness` (NFR-11), `RetryPolicy`.
- `adapter/memory/`: thread-safe in-memory adapters, the only ones that exist. DynamoDB (conditional put, CAS) and SQS (redelivery) are described in the ADRs, not implemented.
- `config/`: `fanout.*` properties for every "to agree" value (retry 5 / 1 s / 30 s, limiter, 25% firm share, DLQ retention 14 d, lease 2 min, batch size, region). Defaults are placeholders, e.g. `max-in-flight=50` is not an agreed number.

## Spec 01 capture and backfill

Exists: capture hooks (`CaptureHooks`), durable outbox, `CaptureRelay`, the regional `TemplateVersionRecordStore`, the rate-limited `BackfillJob`, and record-backed adapters (`RecordBackedTemplateVersionIndex`, `RecordBackedBaselineLookup`) behind `fanout.capture.enabled=false`. Not done:
- Nothing schedules `CaptureRelay.drainOnce`, `CaptureHooks.retryFallback` or `BackfillJob.step` in the P2 wiring; a scheduler is a production concern.
- FR-7: backfill load failures retry forever with capped exponential backoff; none is given up. Flag-off EFs are deferred and re-checked on each step (no load while off).
- An accept that finds no record is retried with backoff and dead-lettered at the attempt limit (reason "no record for accept"), never acked as success.
- The hook fallback queue (outbox down) is in memory and lost on a process crash.
- The store timeout is measured with the clock, not enforced; a hard timeout belongs to the real client.

## Spec 04 pending-update status

What exists (package `status`, wired by `StatusConfiguration` only when `fanout.status.enabled` and `fanout.capture.enabled` are true; flag off means no beans and inert):

- `StatusDeriver`: pure derive of UP_TO_DATE / UPDATE_PENDING / UNKNOWN from the template-version record, the version graph and the decisions. Any failure is UNKNOWN, never UP_TO_DATE. A decision for a version the graph does not know is skipped, so bad stored data cannot poison the derive.
- `StatusStore` (materialised row per EF), `StatusService` (refresh under a per-EF lock, `markUnknown`, `refreshFirm`), the `StatusRefreshingMarker` decorator (refreshes the row when the fan-out marks an EF), `DecisionService` (accept and decline, append-only) and `PendingUpdateQuery` (list and detail).
- Effective baseline after an accept: the newer of the record baseline and the latest accepted version, so the status is right before the capture hook's baseline lands through the outbox. A decline never moves the baseline. Q8: after declining the latest eligible version no older candidate is offered; the EF shows UP_TO_DATE until a newer eligible version appears.
- Decision input is not trusted: the owning firm comes from `EfFirmLookup`; an EF owned by another firm or unknown to the lookup is rejected (`NOT_YOUR_FIRM`), and an unknown version is rejected (`UNKNOWN_VERSION`), in both cases before anything is recorded or any hook runs.
- The list reads rows only (one page plus counts, never an EF load). `refreshFirm` is the catch-up for rows that are missing; it scans the whole region (stand-in for a firm index).
- A duplicate accept or decline is not re-applied: it does not call the hook again, so a hook failure on the first call is repaired by the next open/save capture or reconciliation, not by a repeated click.

Limits:

- W1 (design risk found by QA, no mechanism added): when the status store is down and a healthy UP_TO_DATE row exists, a failed refresh or `markUnknown` put is swallowed and counted by `StatusRefreshingMarker`, so the old row stays until reconciliation repairs it. Reconciliation and its scheduler are not wired in Part 2.
- The Angular UI is not built; it is planned after spec 05.

## Requirement map

FR-14 paged index scan per region, never an EF load. FR-15 conditional put on (EF, template, target). FR-16/17 marker call, skipped for DONE items, monotonic CAS. FR-18/19 backoff with full jitter, then DLQ plus `markUnknown`. FR-20 supersession checked at execution time. FR-21 per-publish status derived from stored item states. NFR-8/9 AIMD limiter (halve on throttle or >10% errors in 1 min, +1 per limit's worth of successes). NFR-10 lease with expiry. NFR-11 work-conserving firm cap. NFR-12 order-independent final state.

## Tradeoffs

- **The downstream call is a marker write, not an EF load.** ADR 0005 is Proposed and Q1 (owner, latency, capacity of that endpoint) is unanswered; the endpoint is an ASSUMPTION. If the real call is the ~1 min EF load, NFR-22 and NFR-13 cannot hold. The port isolates this.
- **At-least-once with a monotonic CAS**, not exactly-once. A crash between marker write and completion re-calls the marker; the second call is a no-op by design. A stale lease holder may also call the marker twice.
- **AIMD over a fixed rate.** Adapts to throttling and error rate without knowing the downstream's real limit; costs a ramp-up and more tuning knobs.
- **Firm cap is work-conserving (25%, to agree).** It binds only while another firm has queued work. NFR-11 conflicts with NFR-13 for a 40,000-EF firm: at 25% share its items need roughly 4x longer than alone. Accepted, not solved.
- **Dead-lettered is terminal, reported separately (Q4).** A publish is complete when every item is terminal, but the status shows the dead-letter count so it is never shown as clean.
- **Unknown clearing (decided, user approved):** a successful `record` of the same or a newer target clears `unknown`, so DLQ replay or reconciliation fixes the EF (ADR 0006, clarification 6).

## Arithmetic

800,000 EFs, 40 products, ~1 publish per product per week: ~800,000 items/week, ~20,000 per publish. At the 2,000 calls/s burst target a publish drains in ~10 s, but real throughput is capped by the in-flight limit and downstream latency, so that is a capacity statement for Q1, not a measured result. ~800,000 conditional writes/week (~1.3/s averaged). Cost in dollars: $X, unset (Q14).

## Known gaps and open decisions

- Resolved: FR-21 status is derived from the work item store (`countByPublish`), so a crash after the terminal CAS cannot lose a count. Cost: each status read scans a publish's items (in-memory scan here; a production store needs a publishId index or a GSI).
- A crash can leave duplicate DLQ entries; the sink does not dedup by key.
- Resolved: FR-20 skips (SUPERSEDED) a target that is withdrawn/ineligible or older than the latest eligible, with no marker call. The check still fails open (item is processed) if the graph read throws or does not know the version.
- A `FlagOff` firm gets `SKIPPED(FLAG_OFF)`; its status "unknown" relies on the read side and reconciliation.
- Lease must exceed the worst-case marker call, or a live call can be re-leased.
- The backlog gauge counts firms with ready work, not items (`countByPublish` is per publish, not a global backlog). The publishId-to-publishedAt lookup for the latency timer is in memory and lost on restart.
- No real scheduler, no AWS adapters, no consumer for SQS.

## Not testable here

NFR-13 against the real downstream, NFR-24 (read isolation), NFR-26 (cost), NFR-31 (availability), alert delivery (NFR-39), real SQS/DynamoDB semantics (visibility timeout, conditional-write races).

## Spec 05 summary generation

What exists: `summary.SummaryService` (ensure, find, processNext, supersede, onPublished), `SummaryQuery` (per-EF view behind the status query), `SummaryPublishListener` (called explicitly, not wired into `PublishHandler` or `Dispatcher`) and `config.SummaryConfiguration`. One summary per (template, baseline, target) pair, shared across firms, appended once and never updated; failures and rejected model output store nothing and retry with capped exponential backoff (no jitter) up to `maxAttempts`, then the DLQ.

- **In-memory only.** Store, queue, single-flight, diff source and baselines are in-memory. The real adapters are not built: S3 with Object Lock (store), DynamoDB (index and leases), SQS plus DLQ (queue), a real pinned model client.
- **Flags.** `fanout.summary.enabled` (default false) additionally needs `fanout.status.enabled` and `fanout.capture.enabled`. `fanout.summary.pinned-model` and `fanout.summary.prompt-version` are required; an alias such as `latest` fails startup (NFR-17). Optional: `max-attempts`, `lease-ttl`, `base-backoff`, `max-backoff`. Per-firm flag off gives no view and no job (NFR-41).
- **Stub model.** No `ModelClient` bean is created by default. `fanout.summary.stub-model=true` declares the deterministic `FakeModelClient`; it is for tests and demos, never production.
- **No scheduler.** Nothing calls `processNext()` in this module; whoever hosts it must. Until then a pair stays "generating".
- **Generating state.** The view is `Generating` while a job is outstanding, `Ready` (labelled "AI-generated") when stored, `Unavailable` ("summary unavailable") on a tampered record, a store failure or a dead-lettered pair. A summary problem never changes or fails the status. A dead-lettered pair is not retried on page loads; an operator redrive (or a supersede) is needed.
- **Supersede** is explicit and synchronous: it appends a new record and moves the pointer; the old record stays readable.
- **Defaults for open questions.** Q5: no timeout on on-demand generation (the view just shows generating). Q6: 7-year retention is an Object Lock concern of the real store, not modelled. Q7: cost X is unset.
- **Known gaps.** Publish-to-ready latency (NFR-20) is indicative in-memory only. The request time is held in memory, so it is lost on restart (no latency sample then). Graph queries that fail for a baseline are skipped and counted (`skippedBaselines()`), not retried, until the next publish or an on-demand request. The Angular UI is not built.

### Spec 05 resilience fixes (QA-1 to QA-6)

- **Deterministic generation ids.** The id is derived from the pair and an ordinal (`gen-<sha256 prefix>-<n>`): 1 for the first record, history size + 1 for a supersede. A retry after a failed pointer move, or a late worker after a lease takeover, re-appends the same id (append is idempotent), so history holds no orphan and the first record wins even if the late model output differs. A record already stored but not current is reused without a new model call.
- **Lease busy.** `processNext` no longer acks the job when another worker holds the lease. It calls the additive `SummaryJobQueue.defer(job, delay)` (same attempt, `baseBackoff` delay), so if the holder died the job still produces the summary and no attempt is burned.
- **Errors.** Exceptions and non-fatal `Error`s from the model, diff source or store are a failed attempt (retry, then DLQ); the lease is always released. A `VirtualMachineError` is rethrown after the job is requeued and the lease released. `EvalHarness` treats a `RuntimeException` from a client like a `ModelException`.
- **Raw-path rule** (exact text in the `SummaryValidator` Javadoc): rejected are diff paths, `$.`, `/a/b` and `[n]` forms, and dotted tokens that have a numeric segment next to a non-numeric one, 3+ segments, or two lowercase-initial segments. Version numbers, an abbreviation allowlist and uppercase joins such as `ends.The` pass. Residual: `done.next` (lowercase join) is a false positive; `Sections.Title` is a false negative.
- **Known gaps remaining.** No DLQ redrive API. DLQ retention is not enforced by the in-memory adapter. `ensure` on a dead-lettered pair returns Failed until a supersede or publish replay. The `ModelId` alias check is a deny-list. A failed pointer move during a supersede can leave one unreferenced history record on retry (the ordinal moves on).

## Web layer and demo (DEV ONLY)

What exists: package `web` (controllers, DTO records, `CallerResolver`, `ApiExceptionHandler`, `DecisionApi`, `WebConfiguration`) and package `demo` (`DemoApplication`, `DemoSeed`). The Maven build now has `spring-boot-starter-web`, the Spring Boot Maven plugin and, for tests only, `spring-boot-starter-test`. The `web` beans exist only with `fanout.web.enabled=true` (default false) plus `fanout.status.enabled` and `fanout.capture.enabled`; the summary endpoint also needs `fanout.summary.enabled`. Region of the cell: `fanout.web.region` (default `EU`). Logic stays in the existing services; controllers only map.

**Headers are a stub, NOT AUTH.** The caller comes from `X-Dev-Firm` and `X-Dev-User`, which any client can set. `CallerResolver` is the only reader of those headers; a missing or blank header is `401 {"error":"UNAUTHENTICATED"}`. A production resolver would read validated JWT claims. The firm is never read from a path, query or body.

Endpoints under `/api/v1/pending-updates`:

| Call | Result |
|---|---|
| `GET ?size=50&token=` | `200 {rows,nextToken,totals}`; size clamped 1..200, non-numeric 400; flag off 404 `FEATURE_OFF`; status read failure 503 `STATUS_UNAVAILABLE` ("unknown, not up to date") |
| `GET /{efId}` | `200 Row`; foreign, unknown or flag off: 404 `NOT_FOUND` (same body, NFR-33); the flag-off detail is 404 `NOT_FOUND` by design, to stay indistinguishable from a foreign EF |
| `GET /{efId}/summary` | `200` with state `READY` (label, generation id, time, model, prompt version, baseline, target, statements with diff item ids, diff as id/section/change, never the path), `GENERATING` or `UNAVAILABLE`; any summary failure is 200 `UNAVAILABLE`, never 5xx |
| `POST /{efId}/accept`, `/decline` with `{"version":"v6"}` | `200 {result: RECORDED or DUPLICATE, row}` (row null if the refresh failed); no identity headers 401 (checked before the body is read, so a bad body without headers is 401), flag off 404 `FEATURE_OFF`, foreign or unknown EF 404 `NOT_FOUND`, malformed or missing body or version 400 `BAD_REQUEST`, version unknown to the graph (or an EF with no known template) 422 `UNKNOWN_VERSION`, then **422 `STALE_OR_NOT_PENDING`** unless the row is `UPDATE_PENDING` and the version equals its `pendingTarget` (stale version, up-to-date EF and UNKNOWN row alike; nothing is recorded, an UNKNOWN row cannot be flipped by a user decision), store failure 503 `DECISION_UNAVAILABLE` |

The template of a decision is taken from the EF's current status row, never from the client. Check order is the one in the table row above, top to bottom. A repeat of the same decision returns 200 `DUPLICATE` while the target is still pending in the row; once the refresh has made the row non-pending, the same repeated POST is 422 `STALE_OR_NOT_PENDING`. That is accepted: the UI always re-reads the row after a decision.

Run the demo (port 8080, profile `demo`, which turns all flags on, uses the stub model and sets `fanout.summary.max-attempts=1`):

    ./mvnw spring-boot:run -Dspring-boot.run.profiles=demo
    curl -H 'X-Dev-Firm: firm-demo' -H 'X-Dev-User: demo-user' localhost:8080/api/v1/pending-updates

Seeded for `firm-demo` (invented ids, in memory, lost on restart): `ef-1001` up to date; `ef-1002` pending v5 to v6 with a READY summary; `ef-1003` pending v3 to v6 (one cumulative update) with GENERATING (job queued, nothing drains it); `ef-1004` unknown (no record); `ef-1005` unknown from a fan-out dead letter; `ef-1006` pending v4 to v6 whose generation failed and dead-lettered (UNAVAILABLE); `ef-1007`, `ef-1008` pending v5 to v6 for trying accept and decline. `firm-other` has `ef-2001`, which `firm-demo` sees as 404.

Not done: no authentication, no CORS configuration, no scheduler (so GENERATING stays), no persistence, no rate limiting, no OpenAPI file.
