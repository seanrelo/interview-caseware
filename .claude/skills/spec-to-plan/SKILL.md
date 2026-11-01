---
name: spec-to-plan
description: Create an implementation plan for one spec or a group of specs in specs/, delegated to the orchestrator agent. Covers every functional and non-functional requirement, writes the plan to specs/plan/, records each architecture decision as its own ADR in docs/ADR/, and, when the review finds problems, updates the mockups and issues a new version of the spec without inventing requirements. Use when the user asks to plan, break down or estimate a spec, e.g. "plan spec 03" or "plan all pending specs".
---

# Spec to plan

Turn EARS specs into an implementation plan that accounts for every FR and NFR, with architecture decisions recorded as ADRs.

## Inputs

- One or more specs, passed as the skill argument: a file (`specs/03-publish-fanout.md`), a number (`03`), a list (`03 04`), `all pending`, or requirement IDs (`FR-14..FR-21`). Resolve numbers and `all pending` through `specs/00-index.md` (Status column).
  - No argument: read the index and ask which specs to plan. Never guess.
- Read first: `CLAUDE.md`, `specs/00-index.md`, every requested spec fully, the specs under "Depends on", `specs/06-cross-cutting.md` (its NFRs apply to every plan), existing mockups in `specs/mockups/<slug>/`, existing ADRs in `docs/ADR/`, existing plans in `specs/plan/`.

## Outputs

| Output | Location | Owner |
|---|---|---|
| Plan, one per spec | `specs/plan/<spec-slug>-plan.md` (for example `03-publish-fanout-plan.md`) | orchestrator, with inputs from the agents it assigns |
| ADRs, one decision per file | `docs/ADR/NNNN-short-title.md`, plus `docs/ADR/README.md` index | architect |
| Mockup updates (only if the review finds issues) | `specs/mockups/<spec-slug>/` in place | frontend, following the `spec-to-mockup` rules |
| New spec version (only if the review finds issues) | `specs/<spec-slug>.md` updated; previous version kept at `specs/archive/<spec-slug>.vN.md` | orchestrator |

Existing plan: update it in place and keep its history in a "Changes" section. Never write elsewhere than the locations above.

## Delegation

The orchestrator agent owns the plan. Subagents cannot spawn subagents, so run it in two steps:

1. **Launch the orchestrator** (`subagent_type: "orchestrator"`) with the brief below. It evaluates the specs and returns the delegation plan: which tasks go to `architect`, `backend`, `frontend`, `qa`, in which order, and which are independent.
2. **Execute that delegation from the main session**: launch the agents it names (independent ones in parallel, with the contracts the architect produced passed to the others), then send the results back through the orchestrator brief for consolidation into the plan files. If the orchestrator can spawn agents directly in this environment, let it.

Brief to the orchestrator (fill in the specs):

> Evaluate spec(s) <list> and produce an implementation plan per spec in `specs/plan/`. Consider every functional and non-functional requirement of the spec and every NFR of `specs/06-cross-cutting.md` that applies; list the requirement IDs that apply and the ones that do not, with the reason. Assign: `architect` the ADRs (one file per decision in `docs/ADR/`, with options and tradeoffs, attribute-driven); `backend` the Java/Spring tasks; `frontend` the UI tasks; `qa` the test strategy and a requirement-to-test table. Do not invent requirements: if something seems missing, record it as an open question. Review the existing mockups and the current spec text; list concrete problems (contradiction, ambiguity, missing state, unmockable wording) and propose fixes that only restate or clarify existing requirements.

## Plan structure

Each `specs/plan/<spec-slug>-plan.md` is under about three pages and contains:

1. **Header**: link to the spec (and its version), date, specs it depends on, status.
2. **Goal and scope**: one paragraph; what is out of scope (cite `CLAUDE.md`).
3. **Requirement coverage**: a table with one row for **every** FR and NFR of the spec, plus the cross-cutting NFRs that apply. Columns: ID, how it is satisfied (component or mechanism), task(s), test(s), ADR(s). A requirement with no task is a defect; if it is not implementable (for example "to agree"), say what is done instead (a configurable limit, a metric).
4. **Architecture decisions**: links to the ADRs, one line each.
5. **Work breakdown**: ordered tasks with ID (T1..), owner agent, requirement IDs, dependencies, definition of done. Mark what can run in parallel.
6. **Test strategy** (qa): cases per requirement, failure injection, what is not testable and why.
7. **Risks and open questions**: including the riskiest part, assumptions (cite index assumption numbers), and items waiting for the user.
8. **Review findings**: problems found in the spec or mockup and what was done (see below).
9. **Changes**: dated log.

Coverage rule: derive the ID list mechanically from the spec (grep `FR-\d+`, `NFR-\d+`), then check the table against it. Hard constraints from `CLAUDE.md` (no EF load on read path or per publish, residency, immutability, failure never "no update", additive and feature-flagged rollout) must show up where they apply.

## ADR rules

- One decision per file: `docs/ADR/NNNN-short-title.md`, numbered sequentially from the highest existing number (start at `0001`). Never merge two decisions, never renumber. Maintain `docs/ADR/README.md` as a table (number, title, status, requirement IDs).
- Format:
  - Title, Status (Proposed, Accepted, Superseded by NNNN), Date.
  - **Context**: the forces and the quality-attribute scenarios that drive it, with FR/NFR IDs.
  - **Options considered**: at least two, each with pros and cons.
  - **Decision**: one paragraph, the option chosen and why.
  - **Tradeoffs and consequences**: which attributes improve, which get worse, risks, what is left out. Include arithmetic when scale or cost matters (800,000 EFs, 4,000 firms, 40 products, ~1 publish/product/week).
  - **Requirements**: IDs affected.
- Attribute-driven: tie each decision to the attribute it protects. Numbers marked "to agree" stay "to agree".
- Check existing ADRs first; if a decision already exists, link it instead of duplicating, or supersede it explicitly.

## Review and improvement (mockups and spec)

During evaluation the orchestrator checks spec and mockup for: contradictions between requirements, an ambiguous or untestable statement, a state or branch missing in the mockup, a failure shown as success, text in the mockup that contradicts a requirement, requirement IDs not tagged in the mockup.

- **Mockup problems**: hand to `frontend`; update `specs/mockups/<spec-slug>/` in place (same rules as `spec-to-mockup`, README coverage table refreshed).
- **Spec problems**: create a new version.
  1. Copy the current file to `specs/archive/<spec-slug>.vN.md` (N = the version being replaced; version 1 if the spec has no version line).
  2. Edit `specs/<spec-slug>.md` in place: add or bump a `Version: N+1` line under the title and add a short "Changelog" section listing each change with the requirement ID.
  3. Allowed changes: clarify wording, fix a contradiction, split a compound requirement into two with new IDs only if the index ranges are updated, align terms with the index. **Not allowed: adding behaviour, new numbers, or new requirements that the source and existing specs do not state.** A genuine gap becomes an open question in the plan and is raised to the user, not a requirement.
  4. If IDs or ranges change, update `specs/00-index.md` accordingly.
- Record every such change under "Review findings" in the plan.
- If a mockup or spec was changed, tell the user explicitly which files.

## Process

1. Resolve the specs; read everything listed under Inputs.
2. Launch the orchestrator with the brief; execute its delegation; architect first when decisions shape other tasks.
3. Write or update the plan files and ADRs; apply mockup and spec updates if findings exist.
4. Launch `qa` to check traceability: every ID of each spec appears in its plan's coverage table; every task cites IDs; every ADR is linked from a plan and listed in the README index.
5. Fix gaps, then report.

## Quality checklist

- [ ] Every FR and NFR of each requested spec has a row in the plan's coverage table; applicable cross-cutting NFRs listed too.
- [ ] Each plan is at most about three pages, with sharp decisions rather than hedging.
- [ ] One ADR per file, numbered, with at least two options and explicit tradeoffs; README index updated.
- [ ] No invented requirement, number or target; "to agree" values preserved.
- [ ] Any spec change has an archived previous version, a version line and a changelog; no other spec was touched.
- [ ] Any mockup change is in place under `specs/mockups/<spec-slug>/` and its README is current.
- [ ] Domain terms match the index.
- [ ] Nothing written outside `specs/plan/`, `docs/ADR/`, `specs/mockups/`, `specs/archive/` and the spec being versioned (plus the index when ranges change).

## Report to the user

Give: the plan files (with the specs they pair with), the ADR list (number, title, one-line decision), requirement coverage per spec (count covered of total and any gap), review findings with the files changed (mockups, spec versions), open questions that need a user decision, and what was not run or verified.
