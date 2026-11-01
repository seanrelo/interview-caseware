# Caseware Take-Home: Template Update Notifications

Architecture design exercise (Staff Java Developer). The system tells users which Engagement Files (EFs) have pending Product Template (PT) updates, with an AI-generated summary of the changes, without ever loading an EF on a read path or per publish.

## Where to start

| Deliverable | Location |
|---|---|
| Design document (decisions, scale arithmetic, risks, what was left out) | [docs/02-architecture.md](docs/02-architecture.md) |
| Part 2: template-publish fan-out worker (Java 21, Spring Boot, in-memory ports) and its README on tradeoffs | [fanout-worker/](fanout-worker/README.md) |
| Architecture decision records (one decision per file, with later clarifications) | [docs/ADR/](docs/ADR/README.md) |
| Requirements in EARS format (FR-n, NFR-n), one spec per area | [specs/](specs/00-index.md) (finished specs in `specs/archive/`) |
| Implementation plans and their change logs | [specs/plan/](specs/plan/) |
| Minimal Angular UI (list, detail, summary panel) | [pending-update-ui/](pending-update-ui/README.md) |
| UI mockups (design reference) | [specs/mockups/](specs/mockups/) |
| How AI was used, corrected and not trusted | [docs/03-ai-use.md](docs/03-ai-use.md) |
| Original brief | [docs/inputs/take-home-test.md](docs/inputs/take-home-test.md) |

## Run it

- Worker tests (from `fanout-worker/`): `./mvnw -q test`. Single class: `-Dtest=ClassName`.
- Demo API with seeded in-memory data (dev-only header identity, not authentication): `./mvnw spring-boot:run -Dspring-boot.run.profiles=demo` from `fanout-worker/`.
- UI (from `pending-update-ui/`): `npx ng test --watch=false`, `npx ng serve` (proxies `/api` to port 8080).

## State

- Spec 03 (fan-out worker) is complete and archived. Specs 01 and 02 are archived. Specs 04 and 05 are implemented in Java and in a minimal UI, and stay "In progress" because the mockup extras were deliberately cut (listed in the UI README).
- Last verified run: 528 Java tests and 35 UI tests, 0 failures. Everything runs in memory: no real AWS, no real model, no real browser run.
- The riskiest assumption is ADR 0005 (the per-EF marker write and the capacity of whoever owns it), still `Proposed`. See section 7 of the design document.
