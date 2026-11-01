---
name: requirements-to-ears
description: Convert a general requirements document (prose, brief, notes, user stories, exercise prompt) into a set of structured specs using EARS (Easy Approach to Requirements Syntax), one spec per functional area plus an index and a cross-cutting NFR spec, with functional (FR-n) and non-functional (NFR-n) requirements kept separate. Use when the user asks to turn requirements, a brief or a problem statement into an EARS spec, or to rewrite requirements in EARS format.
---

# Requirements to EARS spec

Turn an unstructured requirements source into the `specs/` folder as Markdown specs **organized by functionality**, each tracked with an implementation status (all start as `Pending`), where every requirement is a single, testable EARS sentence, classified as **Functional** or **Non-functional**.

## Inputs

- A source document, passed as the skill argument (e.g. `/requirements-to-ears docs/inputs/take-home-test.md`). The argument is a file path, relative to the repo root or absolute. Read it fully before writing.
  - If the path does not exist, search for a close match (e.g. under `docs/inputs/`); if there is exactly one, use it and say so, otherwise ask which file.
  - If no argument is given, fall back to pasted text or the current conversation; if none of those holds a requirements source either, ask for the path. Never guess a source.
  - Several paths may be given; treat them as one combined source and cite which file each doubt comes from in "Assumptions to state".
- Optional: output folder (e.g. `--out specs/`). Default `specs/` at the repo root (create it if missing). Do not write specs under `docs/`. If `specs/` already has specs, add to them and keep existing IDs and statuses. If a single-file `docs/NN-requirements.md` already exists, keep its IDs and split it into `specs/`, not renumber.
- If the source is ambiguous in a way that changes requirements, ask at most 3 questions; otherwise proceed and record the doubt under "Assumptions to state".

## EARS templates

| Pattern | Template | Use for |
|---|---|---|
| Ubiquitous | The `<system>` shall `<response>`. | Always-true behaviour or property |
| Event-driven | When `<trigger>`, the `<system>` shall `<response>`. | Reaction to a discrete event |
| State-driven | While `<state>`, the `<system>` shall `<response>`. | Behaviour during a condition |
| Unwanted behaviour | If `<condition>`, then the `<system>` shall `<response>`. | Failures, errors, bad input |
| Optional feature | Where `<feature is present>`, the `<system>` shall `<response>`. | Configurable or variant behaviour |
| Complex | Combine While/When/If (in that order) | Only when one pattern cannot express it |

Rules:
- One `shall` per requirement. Split compound sentences ("and" joining two behaviours = two requirements).
- Name the system (or component) explicitly; never "it".
- Active voice, present tense, no "should", "may", "etc.", "as appropriate".
- Every requirement must be verifiable. For quality attributes, give a measurable target (number, percentile, unit). If the source gives none, propose one marked "to agree" (e.g. `p95 <= 10 s (to agree)`), never invent silently.
- Keep numbers from the source verbatim; do not round or change them.
- Use the domain terms of the source; define them once in a Terms line.

## Functional vs non-functional

Classify each statement by this test:

- **Functional (FR)**: what the system *does* for a user or another system: features, rules, workflows, data it must record, decisions it must make, behaviour on events and on business-rule failures.
- **Non-functional (NFR)**: how *well* or under what *constraints* it does it: performance, latency, scale, cost, availability, reliability/recovery, consistency, security, privacy, data residency, compliance/audit, retention, observability, operability, rollout/migration constraints, maintainability.

Edge cases:
- Failure handling is NFR when it is about system resilience (retry, DLQ, self-healing); FR when it is a user-visible business rule (e.g. "flag the file for the user").
- A constraint on the solution stack or team ownership is an assumption or constraint, not a requirement, unless it can be stated as a verifiable behaviour.
- Out-of-scope items are not requirements: list them under "Out of scope".

## Output structure

Produce **several specs organized by functionality**, not one monolith. One folder, one index, one file per capability, plus one file for cross-cutting NFRs.

```
specs/
  00-index.md            shared context + map of the specs
  01-<capability>.md     one per functional area (e.g. 01-template-version-capture.md)
  02-<capability>.md
  ...
  NN-cross-cutting.md    NFRs that apply to the whole system
```

### Status

Every requirement, and every spec as a whole, carries an implementation status. A newly generated spec is always `Pending`; never mark anything beyond `Pending` when generating.

| Status | Meaning |
|---|---|
| Pending | Specified, not yet implemented (default) |
| In progress | Implementation started |
| Implemented | Code done, not yet verified against the requirement |
| Verified | Implemented and checked by test or review |

Status is updated later by whoever implements the work, not by this skill. When adding requirements to an existing `specs/`, new rows are `Pending` and existing rows keep their status.

### Splitting into capabilities

- A capability is a cohesive user-facing or system-facing function (a workflow, a rule set, or a data concern), named by what it does: "Version capture", "Update detection", "Summary generation", not by layer or team.
- Target 3-8 capability specs, roughly 5-20 requirements each. Merge specs with fewer than 3 requirements into a neighbour; split specs that mix unrelated workflows.
- Each requirement lives in exactly one spec. Put it where its trigger or subject belongs.
- An NFR that only constrains one capability (e.g. summary immutability, backfill rate) goes in that capability's spec. An NFR that spans capabilities (data residency, rollout, observability, availability) goes in `NN-cross-cutting.md`, grouped by quality attribute.
- Order capability files by user workflow / data flow, so the index reads as a story.

### `00-index.md`

```markdown
# Requirements (EARS): index

EARS templates: Ubiquitous ("The system shall"), Event-driven ("When"), State-driven ("While"), Unwanted behaviour ("If..then"), Optional feature ("Where").

Terms: **X** = ..., **Y** = ...   (defined once here; specs do not redefine them)

## Specs

| Spec | Scope | FR range | NFR range | Status |
|---|---|---|---|---|
| [01-...](01-....md) | one line | FR-1..FR-9 | NFR-1..NFR-3 | Pending |
| [NN-cross-cutting](NN-cross-cutting.md) | system-wide quality attributes | - | NFR-4..NFR-20 | Pending |

Spec status is the least advanced status among its requirements.

## Key numbers
<Derived arithmetic from the source's figures, only if the source has quantities.>

## Assumptions to state
<Numbered, global. Anything inferred, any target proposed, any ambiguity.>

## Out of scope
<Only if the source excludes something.>

## Requirement to challenge
<Only if asked, or if one requirement looks infeasible/contradictory; say why and propose a split or relaxation.>
```

### Capability spec (`NN-<capability>.md`)

```markdown
# <Capability name>

Back to [index](00-index.md). Depends on: <other specs, or "none">.

Status: Pending

Purpose: <one sentence>.

## Functional requirements

### <Sub-group, optional>

| ID | Requirement | Status |
|---|---|---|
| FR-1 | When ..., the system shall ... | Pending |

## Non-functional requirements

| ID | Requirement | Status |
|---|---|---|
| NFR-1 | The system shall ... (p95 <= ... ) | Pending |

## Capability-specific assumptions
<Only if any; otherwise omit. Reference global ones by number.>
```

Omit the "Non-functional requirements" section if the capability has none.

### Cross-cutting spec (`NN-cross-cutting.md`)

Same header (including `Status: Pending`), then `## Non-functional requirements` with `### <Quality attribute>` groups (tables use the same `ID | Requirement | Status` columns) (Performance, Scale and cost, Reliability, Compliance and security, Data residency, Operability, Rollout). No FR section.

### Numbering

`FR-n` and `NFR-n` each run continuously and **globally across all spec files** (not restarting per file), so an ID is unique and citeable without naming its file. Assign in file order. Other documents cite these IDs, so never renumber once published; append new ones at the end of the relevant spec (gaps in a range are fine, update the index table). Cross-spec references use IDs, e.g. "see FR-12".

## Process

1. **Read** the whole source. List every sentence that states a need, constraint, number or rule.
2. **Extract** atomic statements. Drop rationale and solution design (move design hints to Assumptions only if they constrain requirements).
3. **Classify** each as FR or NFR using the test above.
4. **Rewrite** each in the best-fit EARS pattern, one `shall` each, with measurable targets for NFRs.
5. **Cluster** into capabilities (rules above); decide per NFR whether it is capability-specific or cross-cutting.
6. **Number** globally in file order; write the specs into `specs/` with every status `Pending`, then the index with ranges.
7. **Check** (below), fix.
8. **Report** to the user: `specs/` path, a table of specs with FR/NFR counts, assumptions you made, and any source statement you could not turn into a verifiable requirement.

## Quality checklist

- [ ] Every source need is covered by at least one requirement, or listed as out of scope/assumption.
- [ ] No requirement has two `shall`s or an unmeasurable adjective ("fast", "scalable", "robust", "secure").
- [ ] Each requirement uses the EARS pattern that matches its trigger (events use When, failures use If..then, etc.).
- [ ] No FR describes a quality attribute and no NFR describes a feature.
- [ ] IDs are unique, sequential across the whole folder and prefixed correctly.
- [ ] Every requirement appears in exactly one spec; every spec is listed in the index with correct ID ranges.
- [ ] Each capability spec is cohesive and has at least 3 requirements; cross-cutting NFRs are not duplicated inside capability specs.
- [ ] Terms are defined only in the index.
- [ ] Everything is in `specs/` and every requirement row, spec header and index row has status `Pending` (or its preserved prior status).
- [ ] Source numbers are unchanged; any proposed target is marked "to agree".
- [ ] No design or technology choices smuggled in as requirements.
