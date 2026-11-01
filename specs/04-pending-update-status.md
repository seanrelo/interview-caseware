# Pending update status and decisions

Back to [index](00-index.md). Depends on: [01-version-capture](archive/01-version-capture.md), [02-template-version-graph](archive/02-template-version-graph.md), [03-publish-fanout](archive/03-publish-fanout.md).

Status: Pending

Purpose: Show users which EFs have pending updates and record their apply or decline decisions, including declined versions and accumulated updates.

## Functional requirements

### Indicator

| ID | Requirement | Status |
|---|---|---|
| FR-22 | The pending-update view shall show each EF of a firm with one status: up to date, update pending, or unknown. | Pending |
| FR-23 | When an eligible version newer than the EF's baseline exists and is not declined for that EF, the pending-update view shall show the EF as update pending. | Pending |
| FR-24 | While an EF has no template-version record, the pending-update view shall show the EF as unknown. | Pending |
| FR-25 | If the system fails to determine an EF's status, then the pending-update view shall show the EF as unknown and not as up to date. | Pending |

### Accumulation and decline

| ID | Requirement | Status |
|---|---|---|
| FR-26 | When several updates accumulate before a decision, the pending-update view shall present one pending update from the EF's baseline to the latest eligible version. | Pending |
| FR-27 | When a user declines an update, the system shall record a decline for the pair (EF, declined version) with the user, EF, version and time. | Pending |
| FR-28 | While a decline exists for (EF, version), the system shall not present that version as pending for that EF. | Pending |
| FR-29 | When a user declines an update, the system shall leave the EF's baseline unchanged. | Pending |
| FR-30 | When a version later than a declined version becomes eligible for the EF, the system shall present the later version as a pending update from the EF's baseline. | Pending |

### Accept and refresh

| ID | Requirement | Status |
|---|---|---|
| FR-31 | When a user accepts an update, the system shall record the accept with the user, EF, version and time. | Pending |
| FR-32 | When the EF management system processes an accept or decline decision, the system shall refresh the EF's pending-update status. | Pending |

## Non-functional requirements

| ID | Requirement | Status |
|---|---|---|
| NFR-13 | After a PT version is published, the system shall show update pending for affected EFs that have a template-version record within 10 s p95 (to agree). | Pending |
| NFR-14 | The pending-update view shall load a firm's EF statuses within 1 s p95 for a firm with 40,000 EFs (to agree). | Pending |
