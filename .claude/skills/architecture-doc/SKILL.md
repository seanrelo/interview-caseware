---
name: architecture-doc
description: Create or update the architecture and design document (Markdown) from the ADRs in docs/ADR/, delegated to the architect agent. Highlights the key decisions, the technology stack with the reason for each choice, and the pros and cons, links every claim to its ADR, and carries a document-version section. Use when the user asks for the architecture document, design document, or to refresh it after ADRs change, e.g. "write the architecture doc" or "update the design doc".
---

# Architecture doc

Produce `docs/02-architecture.md`: a short, decision-centred architecture and design document that points to the ADRs instead of repeating them.

## Inputs

- Optional argument: a focus or audience (for example "for the live design review"). No argument: write for engineers and reviewers who know the exercise but not the design.
- Read first: `CLAUDE.md`, `specs/00-index.md`, `docs/01-requirements.md`, `docs/ADR/README.md` and every ADR it lists, `specs/plan/*.md` (open questions, risks), existing `docs/02-architecture.md` if present.

## Output

| Output | Location | Owner |
|---|---|---|
| Architecture document | `docs/02-architecture.md` | architect |
| Previous version (only when updating) | `docs/archive/02-architecture.vN.md` | architect |

Write nowhere else. Do not edit ADRs, specs, plans or mockups; a problem found in them goes in the document's "Open points" section.

## Delegation

Launch the `architect` agent (`subagent_type: "architect"`) with the inputs above and the document structure below. The architect writes the file; the main session then checks it (see Checks) and reports. Do not write the document in the main session.

## Document structure

Target 3 to 5 pages. Sharp decisions, no hedging; "(to agree)" values stay "(to agree)" and the cost ceiling X stays unset.

1. **Document version**: first section after the title, a table with: version (`MAJOR.MINOR`), date, author/agent, status (Draft, Reviewed, Approved), ADR set it reflects (list of numbers and each ADR's status at writing time), and a change log (one line per version: date, what changed). A new version bumps this table and keeps the log. Version 1.0 is the first complete document.
2. **Context and drivers**: the problem in one paragraph; the constraints from `CLAUDE.md` that shape everything (no EF load on read path or per publish, residency, immutability, failure is never "no update", additive and flagged rollout); scale numbers with arithmetic.
3. **Architecture overview**: a diagram (Mermaid, fenced) of regional cells and the global layer, plus a short data-ownership table (component, owns, region, ADR).
4. **Key decisions**: a table sorted by architectural impact: decision, one-line rationale, attribute protected, ADR link, ADR status. Highlight the 5 to 7 decisions that matter most in a short paragraph each (what, why, link).
5. **Technology stack**: a table of every technology used (language, framework, store, queue, object storage, compute, observability, UI, model access): choice, why, alternative rejected, ADR. Choices that no ADR states are marked "not decided in an ADR" and listed in Open points; never invent a stack to fill a row.
6. **Pros and cons**: per major decision or area, what improves and what gets worse (cost, complexity, operations, risk), taken from the ADRs' tradeoff sections and checked for consistency; plus the riskiest part of the design and what was deliberately left out.
7. **Quality attributes**: table mapping NFR groups (latency, scale and cost, reliability, compliance, residency, operability, rollout) to the mechanism and ADR.
8. **Open points**: unconfirmed decisions (ADRs with status Proposed), open questions from the plans (keep Q numbers), assumptions from the index.
9. **References**: ADR index link, plans, specs, mockups.

Every statement of a decision cites its ADR as a relative Markdown link (`[ADR 0005](ADR/0005-downstream-call-without-ef-load.md)`). Domain terms match `specs/00-index.md`.

## Process

1. Read the inputs. If `docs/02-architecture.md` exists, determine the next version (minor bump for ADR status or wording changes, major bump for a changed decision) and copy the old file to `docs/archive/02-architecture.vN.md` first.
2. Launch the architect with the structure above and the version to use.
3. Check (below), fix gaps by sending the architect back, then report.

## Checks (main session, after the architect returns)

- [ ] Version section present, first, with ADR set and change log.
- [ ] Every ADR in `docs/ADR/README.md` is linked at least once; no link to a missing file.
- [ ] Every stack row has an ADR or the "not decided in an ADR" mark; nothing invented.
- [ ] Statuses shown match each ADR's Status line.
- [ ] Hard constraints from `CLAUDE.md` appear in Context.
- [ ] No ADR, spec, plan or mockup was modified.
- [ ] Length within about five pages.

## Report to the user

Give: the file written (and archived version if any), the version number, the five highlighted decisions in one line each, stack rows with no backing ADR, ADRs still Proposed, and checks not run.
