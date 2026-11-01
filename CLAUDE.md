# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project status

This is a take-home interview exercise (Caseware, Staff Java Developer), not an application. Documentation lives in `README.md`, `docs/` and `specs/`. `fanout-worker/` (Part 2) now exists: Maven (wrapper `./mvnw`), Java 21, Spring Boot 3, in-memory ports only. See `fanout-worker/README.md`.

Build and test, run from `fanout-worker/`:

- All tests: `./mvnw -q test`
- One class: `./mvnw -q test -Dtest=ClassName`
- One method: `./mvnw -q test -Dtest=ClassName#method`

## The exercise

Design a system that tells users which of their Engagement Files (EFs) have pending Product Template (PT) updates, with a human-readable summary of the changes. Deliverables:

1. Design document (2-4 pages, Markdown or PDF) covering: architecture and data ownership; correctness and production evolution; scale/cost with arithmetic; summary generation and evaluation; tradeoffs, assumptions and the one requirement to challenge. It must also address migration/backfill, unreliable events, observability/SLOs, data residency, failure recovery, the riskiest part of the design, and what was deliberately left out.
2. Part 2 (Java): the template-publish fan-out worker, with a short README on tradeoffs. It must demonstrate concurrency, failure handling, idempotency and respect for downstream capacity. Interfaces, contracts and correctness matter more than completeness; tests are a plus.
3. Optional AI session history, and any diagrams.

The time budget is 4 hours total. The prompt prefers a short document with sharp decisions over a long one that hedges. A live design review follows, where the author must explain where AI helped, where its output was corrected or ignored, and where AI should not be trusted.

## Constraints that drive every design decision

- Loading an EF to read its template ID/version takes ~1 minute and is a hard constraint. That call is owned by another team with limited capacity. 800,000 EFs x 1 min is infeasible to scan, so the design must never load EFs on a read path or per publish. Capture template ID/version through hooks (create, open, save) and backfill existing EFs gradually.
- Template versions form a graph, not a line (market branches, withdrawn versions, a later v7 containing a declined v5). "Declined" must pin (EF, version), not the baseline. Summaries are cumulative from baseline to latest eligible version.
- Data residency: EF data (EU, Canada) stays in-region. The shared global layer holds only PT versions, diffs and summaries, never EF content.
- Summaries may be reviewed in a regulatory audit months later, so they must be immutable and reproducible: store diff, model, prompt version and generation time, with each statement traceable to a diff item. Generate once per (baseline, target) pair and share across firms.
- Event delivery is assumed unreliable: consumers are idempotent (at-least-once), with periodic reconciliation and DLQ. A failure must never be recorded as "no update".
- Rollout has no maintenance window: additive, feature-flagged, reversible.
- Stack: AWS shop. Scale is 4,000 firms, 800,000 EFs, 40 products, ~1 publish/product/week.
- Applying template content is out of scope.

## Documents

- `docs/01-requirements.md`: functional (FR-n) and non-functional (NFR-n) requirements in EARS format. Reference these IDs from later documents. Next planned: `docs/02-architecture.md`.
