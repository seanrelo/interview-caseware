# Template version graph

Back to [index](00-index.md). Depends on: none.

Status: Pending

Purpose: Model template versions as a graph (market branches, withdrawals) and decide which version is the eligible target for an EF.

## Functional requirements

| ID | Requirement | Status |
|---|---|---|
| FR-8 | The template registry shall store for each PT version its template ID, version, parent version(s), market branch and status (published or withdrawn). | Pending |
| FR-9 | When a PT version is published, the template registry shall emit a publish event containing only the template ID and the version. | Pending |
| FR-10 | When a PT version is withdrawn, the template registry shall set the status of that version to withdrawn. | Pending |
| FR-11 | When determining the eligible version for an EF, the system shall select the latest published, non-withdrawn version in the EF's market branch that is reachable from the EF's baseline. | Pending |
| FR-12 | If no path exists from an EF's baseline to any later version in the EF's market branch, then the system shall set the EF's pending-update status to unknown. | Pending |
| FR-13 | When a PT version is withdrawn, the system shall re-evaluate the pending-update status of every EF whose pending version is the withdrawn version. | Pending |

## Non-functional requirements

| ID | Requirement | Status |
|---|---|---|
| NFR-6 | Given the same version graph and the same EF baseline, the system shall select the same eligible version on every evaluation. | Pending |
| NFR-7 | The template registry shall retain the content and diffs of withdrawn versions for as long as any stored summary references them. | Pending |
