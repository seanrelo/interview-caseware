---
name: spec-to-code
description: Implement a spec in specs/ by following its plan in specs/plan/, delegated to the orchestrator agent (backend, frontend, qa). Works task by task, keeps requirement traceability in code and tests, and when the plan cannot be implemented as written, evaluates the tradeoffs and updates the affected ADR (or creates a new one) in docs/ADR/ before changing the approach. Use when the user asks to implement, develop, build or code a spec, e.g. "implement spec 03" or "develop the fan-out worker from its plan".
---

# Spec to code

Implement one spec by executing its plan. The plan is the contract; ADRs record any decision that has to change to make the solution possible.

## Inputs

- One spec, passed as the skill argument: a file (`specs/03-publish-fanout.md`), a number (`03`), or a task range (`03 T1..T4`). Resolve numbers through `specs/00-index.md`.
  - No argument: list specs that have a plan in `specs/plan/` and ask which to implement. Never guess.
- Read first: `CLAUDE.md`, `specs/00-index.md`, the spec fully, its plan `specs/plan/<spec-slug>-plan.md`, `specs/06-cross-cutting.md`, the ADRs the plan links, and `docs/ADR/README.md`.
- **No plan, or a plan that does not cover every FR/NFR of the spec: stop** and tell the user to run `spec-to-plan` first. Do not plan inside this skill.

## Outputs

| Output | Location | Owner |
|---|---|---|
| Java code and tests (Part 2) | `fanout-worker/` (and its README on tradeoffs) | backend |
| UI code and tests | the UI project the plan names; follow `specs/mockups/<spec-slug>/` | frontend |
| Extra tests, traceability check | beside the code (`src/test`, `*.spec.ts`) | qa |
| ADR changes (only when needed) | `docs/ADR/NNNN-short-title.md` plus `docs/ADR/README.md` | architect |
| Plan progress | task status and a dated entry in the plan's "Changes" section | orchestrator |
| Archived spec (only on completion, see "Completion") | `specs/archive/<spec-slug>.md` (moved from `specs/`) | orchestrator |
| Archived mockups (only on completion) | `specs/archive/mockups/<spec-slug>/` (moved from `specs/mockups/<spec-slug>/`) | orchestrator |
| Index status and links | `specs/00-index.md`: Status `Done`, links point to the archive | orchestrator |

Never write outside these locations. Do not edit the spec's content (moving it on completion is the only change); a spec problem becomes an open question for the user (or goes through `spec-to-plan`'s versioning rules).

## Delegation

Subagents cannot spawn subagents, so run it in steps from the main session:

1. **Launch the orchestrator** (`subagent_type: "orchestrator"`) with the brief below. It returns the execution order from the plan's work breakdown: which task goes to `backend`, `frontend`, `qa`, which run in parallel, and what contracts (interfaces, schemas) must exist first.
2. **Execute**: launch the agents it names. Interfaces and contracts first, then implementations, then tests; independent tasks in parallel. Pass each agent its task IDs, the requirement IDs, the linked ADRs and the contracts already produced.
3. **Feasibility loop** (below) whenever an agent reports it cannot implement a task as planned.
4. **Verify**: `qa` runs the tests and checks traceability.

Brief to the orchestrator (fill in the spec and plan):

> Implement spec <spec> following <plan>. Take the tasks in dependency order, assign each to its owner agent, and list contracts to define first. Every task must cite its FR/NFR IDs and ADRs. Do not add behaviour the spec does not state. If a task is blocked or contradicts an ADR, do not work around it: report it as a feasibility issue with the constraint that blocks it.

## Feasibility loop: ADR update or creation

Trigger: a task cannot be done as planned (library limit, downstream capacity, consistency or cost problem, a contradiction between ADRs, a hard constraint from `CLAUDE.md` that the planned approach would break).

1. **State the blocker** in one line, with the requirement IDs and the constraint (use numbers: 800,000 EFs, 4,000 firms, 40 products, ~1 publish/product/week, ~1 min EF load).
2. **Check existing ADRs.** Find the one that covers the decision.
3. **Evaluate at least two alternatives** that make the solution possible (architect agent): pros, cons, effect on each quality attribute, cost arithmetic when relevant. Prefer the smallest change that keeps every `CLAUDE.md` constraint (no EF load on read path or per publish, residency, immutability, a failure never recorded as "no update", additive and feature-flagged rollout).
4. **Record it**:
   - The decision modifies an existing one: set the old ADR to `Superseded by NNNN` and create a new ADR; do not rewrite history. A clarification that changes no decision may be edited in place with a dated note.
   - A new decision: new ADR numbered from the highest existing number.
   - Same format and rules as `spec-to-plan` (Context, Options considered, Decision, Tradeoffs and consequences, Requirements). One decision per file; update `docs/ADR/README.md`.
5. **Ask the user before proceeding** if the decision relaxes a requirement, changes a "to agree" value, or breaks a hard constraint. Otherwise continue and report it.
6. Update the affected plan tasks and add the change to the plan's "Changes" section.

## Completion: archive and index

Only when **all** hold: every task of the plan is done (none deferred), the build and tests ran and passed, and every FR/NFR of the spec is traceable to code and a test with no gap. Otherwise leave the spec and mockups where they are, keep the index Status as it is (set `In progress` if work started), and list what blocks completion in the report.

When they hold:

1. Move the spec: `git mv specs/<spec-slug>.md specs/archive/<spec-slug>.md`. Before moving, check `specs/archive/` for a file of that name; the earlier `<spec-slug>.vN.md` version files stay untouched and the moved file is the current version.
2. Move the mockups, if `specs/mockups/<spec-slug>/` exists: `git mv specs/mockups/<spec-slug> specs/archive/mockups/<spec-slug>` (create `specs/archive/mockups/` if needed). Shared mockup files used by other specs stay in place; ask the user if unsure.
3. Update `specs/00-index.md`: set the spec's Status to `Done` and change its link to `archive/<spec-slug>.md`. Fix relative links in other specs, the plan, ADRs and `CLAUDE.md` that pointed to the old paths (grep the old paths; change only the path).
4. Keep the plan in `specs/plan/` and add a dated "Changes" entry: implemented, archived to `specs/archive/`.
5. Index rule: a spec's Status is the least advanced of its requirements, so `Done` is set only for the spec that just completed; do not touch other rows. A dependent spec that was planned against this one is unaffected.
6. Use `git mv` when the files are tracked (history is preserved), plain `mv` when they are untracked (`git mv` fails on those). Do not commit unless the user asks.

## Implementation rules

- Cite requirement IDs: tests are named or annotated with the FR/NFR they verify; classes that implement a requirement say so in a short Javadoc line. No comment noise beyond that.
- Interfaces and contracts before implementations; behaviour at the failure edges (retries, duplicates, timeouts, partial failure) is tested, not only the happy path.
- Idempotency, concurrency limits, DLQ and "failure is never 'no update'" are tested explicitly where the spec asks for them.
- Match the surrounding code style; prefer the stack in `CLAUDE.md` and the plan.
- Once `fanout-worker/` has a build tool, add the build, test and single-test commands to `CLAUDE.md` (it asks for this).
- Do not commit unless the user asks.

## Process

1. Resolve the spec and plan; read everything under Inputs; stop if the plan is missing or incomplete.
2. Launch the orchestrator; execute its delegation in order.
3. Run the feasibility loop for each blocker; update ADRs and plan.
4. Run the build and tests (`qa`); fix failures in the owning agent, never by weakening a test.
5. Check traceability: every task done or explicitly deferred; every FR/NFR of the spec maps to code and at least one test, or is listed as a gap.
6. Mark task status in the plan.
7. If the completion conditions hold, archive and update the index (below); then report.

## Quality checklist

- [ ] Plan existed and covered the spec before work started.
- [ ] Every task is done, or deferred with a reason in the plan.
- [ ] Every FR/NFR is traceable to code and a test, or listed as a gap.
- [ ] Build and tests were actually run; the result is reported as it is.
- [ ] Each plan deviation has an ADR (new or superseding) with at least two options and explicit tradeoffs; README index updated.
- [ ] No `CLAUDE.md` hard constraint broken; no requirement invented or relaxed without the user's approval.
- [ ] On completion: spec and mockups moved to `specs/archive/`, index Status is `Done`, no broken links remain (grep for the old paths).
- [ ] Not complete: nothing moved, index not marked `Done`.
- [ ] Nothing written outside the locations under Outputs.

## Report to the user

Give: tasks completed and deferred, files created, test command and its real result, requirement coverage (covered of total, gaps), ADRs created or superseded (number, title, one-line decision and the tradeoff accepted), whether the spec was archived and the index set to `Done` (or what blocked it), decisions that need the user, and what was not run or verified.
