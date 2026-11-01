# 0012 Summary quality gate and model pinning

- Status: Accepted
- Date: 2026-10-30

## Context

Hard constraints (CLAUDE.md): summaries are reviewed in audits months later, must be reproducible in what they record (diff, model, prompt version, time) and traceable to diff items. No EF content or identifier goes to a model (NFR-37). Rollout is additive and reversible.

Scenarios:
- A prompt or model change is proposed. It is released only if unsupported statements are at most 1% and omitted diff items at most 5% on the reference set (to agree) (NFR-18, NFR-19).
- A provider changes a model behind an alias. Output must not drift silently (NFR-17).
- A user reads a summary. It is labelled AI-generated (FR-43).

## Options considered

1. **Manual spot checks before release.** Pros: cheap. Cons: no threshold, not repeatable, no audit evidence.
2. **Model alias ("latest") with online monitoring only.** Pros: automatic improvements. Cons: violates NFR-17; behaviour changes without a release.
3. **Pinned model version ID, versioned prompt, reference evaluation set run in CI as a release gate.** Pros: measurable, repeatable evidence, controlled change. Cons: needs a curated set and its upkeep; pinned models get deprecated by the provider.

## Decision

Option 3. Config holds an exact model version ID (never an alias) and a prompt version; both are written to every summary record (ADR 0011, FR-40). A reference set of diffs with human-labelled expected items runs in CI on every prompt or model change. The gate computes unsupported-statement rate (statements whose cited diff items do not support them, judged against labels) and omission rate (labelled diff items not covered by any statement). Release is blocked above 1% unsupported (NFR-18) or 5% omission (NFR-19), both to agree. The view labels each summary AI-generated (FR-43). Set content comes from template diffs only, with no EF data (NFR-37).

Honest limit: even with a pinned model and prompt, LLM output is not guaranteed to be bit-identical on re-run (sampling, serving changes, deprecation). Reproducibility therefore means the stored record is the audit artifact, not that a re-run will reproduce it. Temperature 0 is set to reduce, not remove, variance. The FR-41 validator checks structure, not truth; the gate measures truth statistically on the set, so a bad summary can still pass on an unseen diff.

## Tradeoffs and consequences

- Better: change control, evidence for auditors, no silent drift. Worse: release friction; maintenance of the set; model upgrades require a planned re-run and a decision on the old pairs (keep them; they are immutable).
- Cost: running the set per change = set size x tokens per case (set size and price unset; counts toward NFR-26, X unset).
- Left out: online sampling review of production summaries (recommended follow-up); per-product sets; LLM-as-judge calibration details.
- Risk: the labelled set may not cover rare structures such as big restructures; track and extend it from rejected summaries (FR-41).

## Clarification 2026-11-01 (user-approved, no decision changed)

7. Evaluation harness: a small labelled synthetic set. "Unsupported" = a statement citing a diff item outside its labelled supporting set, or citing nothing. "Omission" = a labelled item cited by no statement. Thresholds of 1% unsupported and 5% omission are to agree and configurable; the runner exits non-zero above them. The model is a pinned ID, never an alias (NFR-17).

## Requirements

NFR-17, NFR-18, NFR-19, FR-43 (also FR-40, NFR-37).
