# How AI was used

Notes for the design review. Written from what happened in the working session, not from intent. Tool: Claude Code, with role agents (architect, backend, frontend, qa, orchestrator) driven by project skills, and me deciding at each fork.

## Where AI helped

- **Structure from a prose brief.** The brief became EARS specs (FR/NFR IDs), ADRs and per-spec plans; every later document and test cites those IDs, which made traceability cheap.
- **Volume of careful, boring code.** The worker (leases, CAS, AIMD limiter, retry/DLQ, fairness), the version graph, the capture and backfill path, the status and summary services, an Angular UI and 528 + 35 tests were produced by agents under written briefs.
- **Independent review.** A separate QA agent with a different brief found defects the builders' own tests did not (below). This was the most valuable use.
- **Arithmetic and checklists.** Scale numbers (13,333 h, 13.9 days at 40 concurrent loads, 2,000 calls/s burst) and the brief's topic checklist.

## Where AI output was corrected

- **Defects found by QA, not by the builders** (each fixed and regression-tested):
  - a crash between the terminal write and the progress count left a publish incomplete forever (counter replaced by completion derived from the store);
  - a withdrawn version could become the pending target;
  - a crafted EF reference let one firm's decision erase another firm's pending update (a security bug; the firm was being trusted from client input);
  - an accept could be lost when its EF's first capture was retried; a precomputed summary job was dropped when a dead worker held the lease; one `Error` stranded a summary pair in "generating"; the raw-path rule rejected "U.S.A." and missed `fields.2.label`;
  - the API accepted decisions for versions that were not the pending target.
- **My own instruction was wrong once.** I told an agent to also record a new index entry when the marker returned UNCHANGED; QA showed that drifts the index. Corrected.
- **Reports were not trusted.** Two agents returned the single word "placeholder" as their report. I checked the files and the test runs directly each time; the work was done, the reports were empty. Counts quoted in this repo are from runs I executed (`./mvnw test`, `ng test`), not from agent summaries.
- **Plans over-reached and were cut.** Orchestrator plans proposed more behaviour than the spec states (version counts, decision logs, EF names, timeouts); these were cut or listed as left out, not faked.
- **Wording ambiguities surfaced, not guessed.** Spec text conflicts (FR-16 vs NFR-22, FR-21 "complete", FR-36 "shall not regenerate" vs immutability, NFR-11 vs NFR-13) were resolved only by explicit decisions recorded as dated ADR clarifications, never by silently picking one.
- **Tooling reality.** The newest Angular CLI refused to run on the installed Node, so the UI uses Angular 21; the CLI default test runner (Vitest) was kept.

## Where AI should not be trusted (and what I did about it)

- **Anything stated as done without evidence.** Rule applied: re-run the suite, read the file.
- **Design decisions that relax a requirement.** Agents propose defaults; each ADR change, port change or scope cut was put to me as a question and recorded. Statuses were not promoted without a decision (ADR 0002, 0004, 0005, 0009 remain `Proposed`).
- **Security and tenancy boundaries.** The cross-firm defect above is the example: tests written by the builder passed because they tested the same assumption.
- **The riskiest assumption.** ADR 0005 (the per-EF marker write is not the one-minute EF load, and its owner has capacity) cannot be settled by an AI; it needs the owning team's answer (Q1).
- **Model behaviour.** Summaries are produced by a stubbed model here. Nothing in this repo shows real model quality; the evaluation harness has only run against fakes. A re-run of a real model is not bit-identical, which is why the design stores the generated record as the evidence.

## What was not verified

No real AWS (DynamoDB, SQS, S3 Object Lock), no real model, no real downstream EF system, no real browser (jsdom only; no contrast or 360 px check), nothing at 800,000 EFs. Timings are in-memory and indicative.
