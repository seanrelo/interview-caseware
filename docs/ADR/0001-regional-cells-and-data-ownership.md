# 0001 Regional cells and data ownership

Status: Proposed (the "baselines in use" export needs user confirmation, index assumption 5)
Date: 2026-10-30

## Context

Hard constraints (CLAUDE.md): never load an EF on a read path or per publish (NFR-21, NFR-22); EF data and EF identifiers stay in the firm's region, EU or Canada (NFR-35); the global layer holds only PT versions, diffs and summaries (NFR-36); no EF content or identifier goes to a model (NFR-37); summaries are immutable and shared across firms; unreliable events with idempotent consumers, reconciliation and DLQ; a failure is never recorded as "no update"; additive, feature-flagged, reversible rollout without a maintenance window (NFR-40..43); AWS; applying template content is out of scope.

Scenario: a template is published (FR-9). Each region must find its affected EFs (FR-14) and write status for them, while the global layer learns nothing about any EF. Scale: 4,000 firms, 800,000 EFs, 40 products (NFR-23); ~40 publishes/week, ~20,000 EFs per publish.

Summaries are precomputed from each baseline "in use" (FR-35). That needs the global layer to know which (template, version) baselines exist, which is a template fact, not EF content (index assumption 5).

## Options considered

A. One global deployment with a region column. Pros: one fan-out, simple. Cons: EF identifiers cross borders (breaks NFR-35); a single blast radius.

B. Regional cells plus a thin global layer. Each region (EU, Canada) is a full cell. The global layer holds registry, diffs, summaries. Pros: residency by construction; cell failure isolated (NFR-30); fan-out scales per region. Cons: two deployments to operate; the registry must replicate its events to each cell.

C. Global layer holds a per-firm, per-EF index of baselines for summary precomputation. Pros: exact precomputation. Cons: EF identifiers in the global layer (breaks NFR-35, NFR-36). Rejected.

## Decision

Option B. Global layer: template registry (FR-8), diffs, summaries, the publish event (FR-9, template ID and version only). Each regional cell owns: template-version records (FR-1..5), pending-update status, decision records (ADR 0009), work items and the fan-out worker (ADR 0006), reconciliation, DLQ. A cell only consumes the global publish event and reads global summaries by (baseline, target); no EF-bearing call goes to the global layer or a model.

Baselines in use are exported from each cell as bare (template ID, version) pairs, a set with no counts, no EF IDs, no firm IDs (to confirm with the user: a count could reveal firm activity). Summaries are generated for each pair (FR-35) and shared (FR-36). Residual risk: a rare pair appearing in only one firm is a weak signal; accepted pending confirmation.

## Tradeoffs and consequences

- Better: residency (NFR-35..37), fault isolation (NFR-30), per-cell scale.
- Worse: two cells to operate, deploy and monitor per region (NFR-38 metrics are per region); duplicated infrastructure.
- Arithmetic: 40 publishes/week x ~20,000 EFs = 800,000 work items/week across both cells (each EF belongs to one product, so at most ~1 item per EF per week). The export is at most 40 products x versions in use, tiny compared with 800,000 EFs.
- Left out: cross-region reporting, multi-region failover of one firm (a firm is pinned to one region).
- Rollout: cells are additive (NFR-40); the flag is per firm (NFR-41).

## Requirements

NFR-35, NFR-36, NFR-37, NFR-23, NFR-30, NFR-40..43, FR-9, FR-14, FR-35, FR-36.
