# 0010 Summary pipeline: key, single-flight, precompute

- Status: Accepted
- Date: 2026-10-30

## Context

Hard constraints (CLAUDE.md): generate once per (baseline, target) pair and share across firms; summaries are immutable and auditable; the global layer holds only PT versions, diffs and summaries, never EF content or EF identifiers (NFR-36, NFR-37); a failure is never "no update"; no EF loads (NFR-21, NFR-22). Scale: 40 products, ~40 publishes/week, ~20,000 EFs per publish, 4,000 firms.

Scenarios:
- A version is published. Summaries from each baseline in use are ready within 5 min p95 (to agree) (FR-35, NFR-20).
- A user opens a pair with no summary. It is generated on demand (FR-34), once even under concurrent requests (FR-36).
- Generation fails or is rejected. The view shows pending plus summary-unavailable and a retry is scheduled (FR-42).
- Monthly cost stays under $X (X not given) (NFR-26).

## Options considered

1. **Key per firm or per EF.** Pros: simple authorisation. Cons: 4,000x duplicates; breaks FR-36; carries EF identity into the global layer.
2. **Key (template ID, baseline, target); on-demand only.** Pros: spend only on viewed pairs. Cons: first viewer waits for the LLM; NFR-20 fails.
3. **Same key; precompute at publish for baselines in use plus on-demand fallback; single-flight per pair.** Pros: fast common path, one generation per pair, cost bounded by distinct pairs. Cons: spend on pairs nobody opens; needs the "baselines in use" set.

## Decision

Option 3. Key = (template ID, baseline version, target version), identical for all firms (FR-36). On publish, the summary service takes the distinct baselines in use for that template ID, which is a set of version numbers aggregated into the global layer from the regional records without EF identifiers (assumption 5), and enqueues one generation per pair (FR-35). A missing pair on open enqueues the same job (FR-34). Single-flight: a conditional put of a lock item `pair-key` with lease TTL in DynamoDB; the winner generates, losers poll or subscribe to the completion event; an expired lease allows takeover. Input is the JSON diff only (FR-37). Failures retry with exponential backoff up to a cap (to agree) and then raise the summary failure metric (ADR 0013); the view meanwhile shows pending plus summary-unavailable (FR-42). Storage and validation: ADR 0011; model pinning: ADR 0012.

## Tradeoffs and consequences

- Arithmetic: pairs per publish = B, the number of distinct baselines in use for that product (no value given, to agree; at most the number of earlier eligible versions). Pairs per week = 40 x B. Cost per month = pairs per month x (input tokens + output tokens) x unit price. Tokens per pair depend on diff size, and unit price on the pinned model; neither is given. Cost ceiling X is unset (NFR-26); the model is a formula to be filled once B, diff size and price are known. Per-firm keys would multiply it by up to 4,000.
- Cumulative pairs avoid chaining summaries, so a summary does not depend on a prior summary (FR-33 uses baseline to latest).
- Better: latency (NFR-20), cost, consistency across firms. Worse: wasted spend on unopened pairs; the lease needs tuning to model latency (to agree).
- Left out: summarising intermediate versions; per-firm wording.
- Risk: the baselines-in-use aggregate is stale between reconciliations; the on-demand path covers the gap.

## Clarification 2026-11-01 (user-approved, no decision changed)

1. Regeneration: a valid stored summary for (template, baseline, target) is always reused and shared across firms (FR-36). A summary is regenerated only when none exists or on an explicit supersede, which appends a new record and moves the current pointer; the old record stays readable for audit (NFR-15, ADR 0011 immutability).
2. "Baselines in use" (FR-35): a port returns bare version IDs per template, with no EF or firm identifiers, restricted to baselines from which the newly published version is the eligible target. A version is not EF content, so residency holds (NFR-36).
3. The pair diff comes from a DiffSource port that reads the stored per-version diff refs. An unknown or unreachable pair is absent, never an empty diff. A DiffSource failure is a failed job, never a summary.
4. Defaults, to agree and configurable: max 5 attempts, exponential backoff with 1 s base capped at 5 min, lease TTL 2 min, DLQ after the cap (NFR-29). No on-demand timeout is invented: the view stays "generating" until a job completes (FR-42).

## Requirements

FR-33, FR-34, FR-35, FR-36, FR-42, NFR-20, NFR-26 (also FR-37, NFR-21, NFR-36, NFR-37).
