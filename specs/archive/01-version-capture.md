# Version capture and backfill

Back to [index](00-index.md). Depends on: none.

Status: Pending

Purpose: Record the template ID and version of every EF, new and existing, without loading EFs on demand.

## Functional requirements

### Capture hooks

| ID | Requirement | Status |
|---|---|---|
| FR-1 | When an EF is created, the EF management system shall record the EF's template ID and version in the template-version record. | Pending |
| FR-2 | When an EF is opened, the EF management system shall record the template ID and version of the opened EF in the template-version record. | Pending |
| FR-3 | When an EF is saved, the EF management system shall record the template ID and version of the saved EF in the template-version record. | Pending |
| FR-4 | When the EF management system processes an accept decision, the EF management system shall record the accepted version as the EF's baseline in the template-version record. | Pending |
| FR-5 | If a capture hook delivers the same EF, template ID and version more than once, then the template-version record shall keep exactly one entry for that EF. | Pending |

### Backfill of existing EFs

| ID | Requirement | Status |
|---|---|---|
| FR-6 | While backfill is enabled, the backfill job shall load each EF that has no template-version record and record its template ID and version. | Pending |
| FR-7 | If loading an EF fails during backfill, then the backfill job shall keep the EF without a template-version record and schedule a retry. | Pending |

## Non-functional requirements

| ID | Requirement | Status |
|---|---|---|
| NFR-1 | The capture hooks shall add zero EF loads beyond those the EF management system already performs. | Pending |
| NFR-2 | The capture hooks shall add no more than 50 ms p95 latency to EF create, open and save (to agree). | Pending |
| NFR-3 | If a capture hook fails, then the EF management system shall complete the EF operation unaffected and queue the capture for retry. | Pending |
| NFR-4 | While backfill is running, the backfill job shall keep concurrent EF loads at or below the limit agreed with the team that owns the EF loading system (limit to agree). | Pending |
| NFR-5 | The backfill job shall record template ID and version for 100% of existing EFs within 14 days of enabling (to agree; 800,000 loads x 1 min = 13,333 h, which needs at least 40 concurrent loads). | Pending |
