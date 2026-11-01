# 0009 Decision records pin (EF, version)

Status: Accepted
Date: 2026-10-30

Status 2026-11-01: promoted to Accepted by user decision.

## Context

Hard constraints (CLAUDE.md): "declined" must pin (EF, version), not the baseline; summaries are cumulative from baseline to latest eligible version; records are auditable for years (NFR-34, 7 years, to agree); no EF load on read path (NFR-21); residency (NFR-35: decisions stay in the regional cell); unreliable events, so decision processing is idempotent; additive, flagged rollout.

Scenarios: v5 is declined, later v7 (contains v5) becomes eligible: show v7 from the baseline (FR-30). A user declines and the baseline must not move (FR-29). A user accepts: baseline becomes the accepted version (FR-4, FR-31). Scale: up to ~20,000 EFs per publish; decisions are human-rate, far below.

## Options considered

A. Store a mutable "declined baseline" per EF. Pros: one row. Cons: the baseline moves or is overloaded; loses which version was declined; fails FR-29 and FR-30.

B. Append-only decision records keyed (EF, version), type accept|decline, with user, time; baseline changes only on accept. Pros: pins the declined version; auditable. Cons: status must be derived.

C. Event-source everything. Pros: full history. Cons: heavier than needed.

## Decision

Option B. Decision record: (EF, version, type, user, time), append-only, never updated or deleted within retention (NFR-34). A duplicate decision for the same (EF, version, type) is a no-op (idempotent). Decline leaves the baseline unchanged (FR-29) and records the decline (FR-27); accept records the decision (FR-31) and sets the baseline in the template-version record (FR-4). Status is derived: latest eligible version (ADR 0004) vs baseline, minus the set of declined versions for that EF: pending if the latest eligible version is newer than the baseline and not declined (FR-23, FR-28). A later version is not in the decline set, so it is presented from the baseline (FR-30, FR-26). The candidate shown is the latest eligible version only; after any decision the status row is refreshed (FR-32, ADR 0008).

Resolved (Q8, approved 2026-10-30, see Clarification item 1): if the latest eligible version is declined and an older undeclined version is also eligible, the status is up to date and the older one is not offered, since FR-26 presents one update to the latest eligible version and FR-28 only blocks the declined version. No behaviour is added by this ADR.

## Tradeoffs and consequences

- Better: audit and reproducibility, correct decline semantics (FR-27..30), idempotent decisions.
- Worse: derivation on each evaluation; decline set grows per EF (small, human-rate).
- Arithmetic: even if every one of 800,000 EFs recorded one decision per week, that is ~1.3 writes/s, trivial against the 7-year retention (to agree).
- Left out: undo of a decision (a new decision is a new record), applying template content.

## Clarification 2026-10-30 (user-approved, no decision changed)

Status stays Proposed.

1. Q8 confirmed by the user. When the latest eligible version is declined, the status is up to date and no older undeclined version is offered, because the candidate is always the latest eligible version shown from the baseline. A later version is not in the decline set and is presented from the baseline (FR-30). A decline never touches the baseline (FR-29). This matches the proposal in the open question above (FR-26, FR-28).

## Requirements

FR-4, FR-26, FR-27, FR-28, FR-29, FR-30, FR-31, FR-32, NFR-34, NFR-21, NFR-35.
