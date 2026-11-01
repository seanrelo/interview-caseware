---
name: spec-to-mockup
description: Create clickable UI mockups in plain HTML, CSS and JavaScript (no build step) from the EARS specs in specs/. Each screen and state cites the FR/NFR IDs it illustrates. Use when the user asks for a mockup, wireframe, prototype or UI sketch of a feature described in a spec, e.g. "mock up the pending-update list from spec 04".
---

# Spec to mockup

Build static, dependency-free HTML/JS mockups that make the behaviour of one or more specs visible and reviewable, and trace every visible element back to a requirement ID.

## Inputs

- One or more specs, passed as the skill argument: a file (`specs/04-pending-update-status.md`), a number (`04`), or requirement IDs (`FR-22..FR-32`). Paths are relative to the repo root.
  - If the argument is a number or ID, resolve it through `specs/00-index.md`.
  - If no argument is given, read `specs/00-index.md` and ask which spec to mock up. Never guess.
- Optional: output folder (`--out <dir>`). Default `specs/mockups/<spec-slug>/`, where `<spec-slug>` is the spec's file name without `.md` (e.g. `specs/04-pending-update-status.md` -> `specs/mockups/04-pending-update-status/`). Create it if missing.
  - Mockup of several specs: use the slug of the primary spec and list the others in the mockup README. Mockup of an ID range inside one spec: still that spec's slug.
  - If the folder already exists, update it in place rather than creating a second one.
  - Only write under `specs/mockups/` (or `--out`). Never edit the spec files themselves, and never write under `docs/`.
- Read the index (for Terms) and every requested spec fully before writing. Also read the specs listed under "Depends on" if the mockup needs their states.

## What is mockable

Mock only requirements with something a user sees or does. Skip the rest and say so in the report.

- Mock: FRs about display, status, decisions, messages, labels (e.g. FR-22..FR-32, FR-33, FR-42, FR-43).
- Mock as visible states: NFRs a user can perceive (e.g. loading, "unknown" on failure, summary unavailable).
- Skip: backend-only behaviour (hooks, backfill, fan-out, retries, residency, rollout). Do not invent UI for them.

## Process

1. **Extract screens and states.** List each user-visible requirement and group them into screens (list, detail, dialog). For each element, note the requirement ID it comes from.
2. **Define the state matrix.** Every status or branch in the spec becomes a switchable state, including failure and edge states (e.g. unknown, summary unavailable, declined-then-later-version, accumulated updates). A failure must never look like the success state.
3. **Write the mock data** as one JS object (`data.js`), using the domain terms from the index. Invent plausible sample values (firm names, EF names, template versions) and mark them as sample data. Keep it small: 5-12 rows covering every state.
4. **Build the pages** (see Output structure). Make the interactions the spec describes work against the mock data: filter by status, open detail, accept, decline, and see the status change per the FRs.
5. **Add the state switcher and requirement tags** (see below).
6. **Check** (below), fix, and report.

## Output structure

```
specs/mockups/<spec-slug>/
  index.html     entry point; links the screens
  styles.css
  data.js        sample data, plain JS object on window.MOCK
  app.js         rendering and interactions
  README.md      link to the source spec, screens, state matrix, requirement coverage, skipped requirements
```

The folder name ties each mockup to its spec: `specs/NN-name.md` <-> `specs/mockups/NN-name/`. The mockup README opens with the relative link to the spec (`../../NN-name.md`), and `index.html` shows the same link and spec title in its header.

Rules:
- Plain HTML, CSS and vanilla JavaScript. No framework, bundler, npm install or CDN. Opening `index.html` from disk must work (no `fetch` of local files, no ES module imports; use `<script src>` in order).
- No real data. No network calls.
- Semantic HTML, labels on controls, keyboard-operable buttons, visible focus, sufficient contrast. Status must not rely on colour alone (add text or icon).
- Responsive down to 360 px width.
- Keep it a mockup: no persistence beyond in-memory state, no auth, no validation beyond what the spec states.

## Traceability

- Every element that realises a requirement carries `data-req="FR-23"` (space-separated for several).
- A toolbar toggle "Show requirements" outlines tagged elements and shows their IDs as small badges. Default off.
- A "State" selector switches the whole mockup between the states of the state matrix. Include a "Reset" button.
- The mockup must never display text that contradicts a requirement. If the spec leaves something open, show a visible "TBD (see assumption N)" note and cite the assumption number from the index.

## README of the mockup

Keep it under one page:

- Source spec: relative link (`../../NN-name.md`), plus any other specs used.
- Screens and how to open them.
- State matrix: state name, which FRs it shows, how to reach it.
- Coverage table: requirement ID, where it appears (screen/element), or "not mocked" with the reason.
- Sample data disclaimer and assumptions made while mocking.

## Quality checklist

- [ ] Every mockable FR in the requested specs appears in at least one screen, tagged with `data-req`.
- [ ] Every status or branch in the spec has a state in the switcher, including failure states.
- [ ] No failure or missing-data state is presented as a success state (e.g. unknown is never shown as up to date).
- [ ] No UI for backend-only requirements; these are listed as skipped in the README.
- [ ] Domain terms match the index; no new terms.
- [ ] Opens from disk with no network or build step; works at 360 px width.
- [ ] Keyboard-operable and not colour-only.
- [ ] Numbers shown (counts, versions, times) come from the mock data, not hard-coded in markup.
- [ ] The mockup lives in `specs/mockups/<spec-slug>/` and links back to its spec.
- [ ] No spec file and nothing under `docs/` was modified.

## Report to the user

Give: the output path (and the spec it is paired with), how to open it, a table of screens with the FR IDs each covers, the requirements skipped as not mockable, and any assumptions or TBD notes shown in the UI.
