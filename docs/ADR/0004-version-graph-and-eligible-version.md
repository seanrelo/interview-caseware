# 0004 Version graph and eligible version

Status: Accepted
Date: 2026-10-30

Status 2026-11-01: promoted to Accepted by user decision; Q7 (how withdrawal is communicated) remains open for 0004.

## Context

Hard constraints (CLAUDE.md): no EF load on read path or per publish (NFR-21, NFR-22); residency (NFR-35, NFR-36: the graph is global, contains no EF data); summaries immutable and reproducible, so withdrawn versions' diffs stay (NFR-7); unreliable events, a failure is never "no update"; additive, flagged rollout (NFR-40..43). Scale: 40 products, ~40 publishes/week, ~20,000 EFs per publish.

Versions form a graph, not a line: market branches, withdrawn versions, a later v7 containing a declined v5 (FR-8). Scenarios: (1) the eligible version for an EF is the latest published, non-withdrawn version in its market branch reachable from its baseline (FR-11), identical on every evaluation (NFR-6). (2) A withdrawal must re-evaluate every EF whose pending version is the withdrawn one (FR-13) without loading EFs. (3) No path from baseline to a later version in the branch gives unknown (FR-12).

## Options considered

A. Linear version numbers (max number wins). Pros: trivial. Cons: wrong with branches and withdrawals; violates FR-11.

B. DAG with parent edges and a status flag; eligibility is a reachability query over the published subgraph. Pros: models FR-8 directly; withdrawal is a flag, content stays (NFR-7). Cons: needs a deterministic rule among incomparable leaves; reachability cost.

C. Delete or rewrite on withdrawal. Cons: breaks audit and NFR-7. Rejected.

## Decision

Option B. Registry stores (template ID, version, parents[], market branch, status published|withdrawn) (FR-8). Withdrawal sets status only (FR-10); nothing is deleted (NFR-7). Eligible version for baseline b in branch m: the set of published versions in branch m reachable from b (descendants through any path, withdrawn versions are traversable as nodes but not selectable, so a path through a withdrawn v6 to v7 still counts; resolved: approved 2026-10-30, see Clarification item 3). If several leaves are incomparable, pick by publish time descending, then version ID descending (resolved: total order key publishedAt then version ID, approved 2026-10-30, see Clarification item 2), which is total and so satisfies NFR-6. No descendants in the branch while a later version exists in the branch: unknown (FR-12). No later version at all: up to date, not unknown.

Reverse index for FR-13: in each cell, an index from (template ID, pending target version) to EF keys, maintained from the status row (ADR 0008). A withdrawal event queries this index and re-evaluates only those EFs, using graph data and the stored baseline, never an EF load.

Graph size is tiny (40 products x tens of versions), so it is cached per cell and evaluated in memory; evaluation is a pure function of (graph snapshot, baseline, branch, decline set).

## Tradeoffs and consequences

- Better: correctness on branches, determinism (NFR-6), auditability (NFR-7).
- Worse: graph version must be part of evaluation inputs; a stale cache can mis-evaluate until reconciliation (NFR-28, 24 h to agree) fixes it.
- Arithmetic: a withdrawal re-evaluates only EFs pending on that version, at most ~20,000 per product (800,000 / 40), so ~20,000 in-memory evaluations, no loads.
- Left out: automated branch merging, version deprecation policy.

## Clarification (user-approved) 2026-10-30 (no decision changed)

Recorded while writing the spec 02 plan (T1). Status stays as above.

1. Result type. `eligibleVersion(template, baseline[, branch])` returns `Eligible(version) | UpToDate | Unknown(reason)`. When `branch` is not given it is derived from the baseline version's own market branch. `Unknown` is returned only when a later version exists in the branch but is not reachable from the baseline (FR-12). When nothing newer exists in the branch the result is `UpToDate`, not `Unknown`. A failure or exception during evaluation is never mapped to `UpToDate` (FR-12, ADR 0008); it surfaces as an error or as `Unknown`.
2. One strict total order. A single key, (`publishedAt`, then version ID), is used both for "latest" (Q3 tie-break, approved: publish time descending, then version ID descending) and for `isNewer` (ADR 0006 clarification 3). Registration invariant: a child's `publishedAt` must be strictly after the `publishedAt` of every parent, and cycles are rejected at registration. So graph order and the total order never disagree along a path, and the result does not depend on insertion order (NFR-6).
3. Withdrawn versions are traversable but not selectable (approved). A path baseline -> withdrawn v6 -> v7 still reaches v7; v6 is never returned as the eligible version (FR-11).
4. Withdrawal is a flag only (FR-10) and is idempotent on repeat (NFR-27). Content and diffs of the withdrawn version stay readable (NFR-7).
5. FR-13 has no defined trigger (Q7 stays open). The re-evaluator is exposed as a direct method call; no event type is invented here.
6. Seam with spec 03. The port `VersionGraphReader { latestEligible, isNewer, isEligible }` stays unchanged. The per-EF `eligibleVersion` lives on a new `VersionGraph` type. `FanoutConfiguration` keeps the legacy stub wired; switching to the real graph is a later one-line change.
7. Q8 (a declined latest eligible version with an older undeclined one) stays with spec 04. The decline set is not an input of `eligibleVersion` in this clarification.

8. Note 2026-10-30 (user-approved; no decision changed): the FR-13 re-evaluator clears or moves a withdrawn pending target through the new `PendingUpdateMarker.reevaluate` write, see ADR 0006 clarification 9.

## Requirements

FR-8, FR-10, FR-11, FR-12, FR-13, NFR-6, NFR-7, NFR-21, NFR-22.
