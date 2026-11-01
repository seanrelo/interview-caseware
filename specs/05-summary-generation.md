# Summary generation

Back to [index](00-index.md). Depends on: [02-template-version-graph](archive/02-template-version-graph.md), [04-pending-update-status](04-pending-update-status.md).

Status: Pending

Purpose: Produce an immutable, traceable, human-readable summary of the cumulative changes between a baseline and a target version, once per version pair.

## Functional requirements

### Generation

| ID | Requirement | Status |
|---|---|---|
| FR-33 | When a user opens a pending update, the pending-update view shall display the summary for the pair (EF baseline, latest eligible version). | Pending |
| FR-34 | When no summary exists for a requested (baseline, target) pair, the summary service shall generate it. | Pending |
| FR-35 | When a PT version is published, the summary service shall generate a summary from each baseline version in use to the new version. | Pending |
| FR-36 | If a summary exists for a (baseline, target) pair, then the summary service shall reuse it for every firm and shall not regenerate it. | Pending |
| FR-37 | The summary service shall generate each summary from the JSON diff between the baseline and the target version. | Pending |
| FR-38 | The summary service shall express each change using section names and shall contain no raw JSON paths. | Pending |

### Traceability and storage

| ID | Requirement | Status |
|---|---|---|
| FR-39 | The summary service shall link each statement of a summary to at least one diff item. | Pending |
| FR-40 | The summary service shall store with each summary its diff, model identifier, prompt version and generation time. | Pending |
| FR-41 | If a generated summary contains a statement with no linked diff item, then the summary service shall reject the summary. | Pending |
| FR-42 | If summary generation fails or is rejected, then the pending-update view shall display the update pending status with a summary-unavailable message and the summary service shall schedule a retry. | Pending |
| FR-43 | The pending-update view shall label each summary as AI-generated. | Pending |

## Non-functional requirements

| ID | Requirement | Status |
|---|---|---|
| NFR-15 | Once stored, the summary service shall not modify a summary; a regenerated summary shall be stored as a new record. | Pending |
| NFR-16 | The summary service shall retrieve, for any summary shown to a user, its text, diff, model identifier, prompt version and generation time for at least 7 years (to agree). | Pending |
| NFR-17 | The summary service shall invoke a pinned model version identifier and no alias. | Pending |
| NFR-18 | The summary service shall not release a prompt or model version whose unsupported-statement rate on the reference evaluation set exceeds 1% (to agree). | Pending |
| NFR-19 | The summary service shall not release a prompt or model version whose omission rate of diff items on the reference evaluation set exceeds 5% (to agree). | Pending |
| NFR-20 | After a PT version is published, the summary service shall have summaries ready for baselines in use within 5 min p95 (to agree). | Pending |
