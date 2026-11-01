# Requirements (EARS): index

EARS templates: Ubiquitous ("The system shall"), Event-driven ("When"), State-driven ("While"), Unwanted behaviour ("If..then"), Optional feature ("Where").

Terms: **EF** = Engagement File, the per-customer work file. **PT** = Product Template, the shared template an EF is created from. **Baseline** = the template version an EF was created from or last accepted. **Eligible version** = a published, non-withdrawn PT version the EF can be offered. **Pending update** = an eligible version newer than the baseline that has not been declined for that EF. **Template-version record** = the stored association of an EF with its template ID and version, readable without loading the EF. **Template registry** = the global store of PT versions. **EF management system** = the system that loads, creates and processes decisions on EFs. **Fan-out worker** = the component that turns a publish event into per-EF work. **Summary service** = the component that generates and stores summaries. **Pending-update view** = the user-facing list and detail of EF update status. **Region** = EU or Canada.

## Specs

| Spec | Scope | FR range | NFR range | Status |
|---|---|---|---|---|
| [01-version-capture](archive/01-version-capture.md) | Hooks and backfill that record template ID and version per EF | FR-1..FR-7 | NFR-1..NFR-5 | Done |
| [02-template-version-graph](archive/02-template-version-graph.md) | Version graph, withdrawal, eligible version | FR-8..FR-13 | NFR-6..NFR-7 | Done |
| [03-publish-fanout](archive/03-publish-fanout.md) | Publish event to per-EF work within downstream capacity (Part 2) | FR-14..FR-21 | NFR-8..NFR-12 | Done |
| [04-pending-update-status](04-pending-update-status.md) | Indicator, accumulation, decline pin, accept | FR-22..FR-32 | NFR-13..NFR-14 | In progress (Java and minimal UI done; mockup extras deferred) |
| [05-summary-generation](05-summary-generation.md) | Immutable, traceable summaries per version pair | FR-33..FR-43 | NFR-15..NFR-20 | In progress (Java and minimal UI done; mockup extras deferred) |
| [06-cross-cutting](06-cross-cutting.md) | Scale, cost, reliability, residency, operability, rollout | - | NFR-21..NFR-43 | Pending |

Spec status is the least advanced status among its requirements.

## Key numbers

- Reading every EF once: 800,000 EFs x 1 min = 800,000 min = 13,333 h (about 556 days single-threaded). At 40 concurrent loads: about 14 days.
- Publishes: 40 products x ~1 per week = ~40 per week (about 6 per day).
- EFs per product, if evenly spread: 800,000 / 40 = 20,000. Loading them all per publish: 20,000 min = 333 h.
- EFs per firm: mean 800,000 / 4,000 = 200; median ~40; largest ~40,000 (5% of all EFs).

## Assumptions to state

1. The source gives no value for $X (cost ceiling); NFR-26 keeps the placeholder.
2. The source says "within seconds" without a number; NFR-13 proposes 10 s p95 (to agree).
3. All "(to agree)" targets are proposals; the source gives none.
4. The fan-out worker's downstream call (FR-16) is the ~1 min per EF call owned by another team. This conflicts with NFR-22 if the call is an EF load. To resolve in design: either the call records a marker without a full load, or fan-out only touches EFs with unknown versions.
5. Summaries are precomputed for baseline versions "in use" (FR-35). Baseline versions are template versions, not EF content, so they can reach the global layer without breaking NFR-36.
6. Labelling summaries as AI-generated (FR-43) is inferred from the source's emphasis on traceability and confidence.
7. A retention of 7 years (NFR-16, NFR-34) is proposed for regulatory review; the source only says "months later".
8. FR-12 (no path from baseline) sets status to unknown; the source does not say what to show.
9. The deliverables of the exercise (design document, Part 2 code and README, AI session history, diagrams) are not system requirements and are not specified here.

## Out of scope

- Applying the updated template content to an EF.

## Requirement to challenge

"Available in real time, within seconds of a template being published" cannot hold for every part of the feature:

- EFs without a template-version record cannot be evaluated until backfill reaches them (NFR-5: up to 14 days), and reading one takes ~1 min.
- LLM summary generation is not a seconds-scale step, and each summary must pass the traceability check (FR-41).

Proposed split: the indicator within seconds for EFs with a record (NFR-13); summaries ready within minutes (NFR-20); unknown (FR-24) for the rest, never "up to date".
