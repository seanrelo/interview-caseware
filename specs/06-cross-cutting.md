# Cross-cutting non-functional requirements

Back to [index](00-index.md). Depends on: all capability specs.

Status: Pending

Purpose: System-wide quality attributes that apply to every capability.

## Non-functional requirements

### Performance

| ID | Requirement | Status |
|---|---|---|
| NFR-21 | The system shall not load an EF to determine pending-update status on any read path. | Pending |
| NFR-22 | The system shall not load an EF in order to read its template ID or version when a template is published. | Pending |

### Scale and cost

| ID | Requirement | Status |
|---|---|---|
| NFR-23 | The system shall support 4,000 firms, 800,000 active EFs and 40 products. | Pending |
| NFR-24 | The system shall meet NFR-14 for the largest firm (~40,000 EFs) while a fan-out is running. | Pending |
| NFR-25 | The system shall process publishes from all 40 products within the same hour (to agree) while meeting NFR-13. | Pending |
| NFR-26 | The system shall keep its all-in run cost, including inference spend, under $X per month (X not given in the source). | Pending |

### Reliability

| ID | Requirement | Status |
|---|---|---|
| NFR-27 | The system shall make every event consumer idempotent under at-least-once delivery. | Pending |
| NFR-28 | The system shall reconcile every EF's stored pending-update status against the version graph at least every 24 h (to agree). | Pending |
| NFR-29 | The system shall retain dead-lettered messages for at least 14 days (to agree). | Pending |
| NFR-30 | If any part of this feature fails, then the EF management system shall continue to create, open, save and process decisions for EFs. | Pending |
| NFR-31 | The pending-update view shall be available 99.9% of each month (to agree). | Pending |
| NFR-32 | After an outage ends, the system shall process the event backlog without losing any event. | Pending |

### Compliance and security

| ID | Requirement | Status |
|---|---|---|
| NFR-33 | The system shall show a user only the statuses of the EFs of that user's firm. | Pending |
| NFR-34 | The system shall retain accept and decline records for at least 7 years (to agree). | Pending |

### Data residency

| ID | Requirement | Status |
|---|---|---|
| NFR-35 | The system shall store and process EF data and EF identifiers only in the region the firm requires (EU or Canada). | Pending |
| NFR-36 | The global layer shall store only PT versions, diffs and summaries. | Pending |
| NFR-37 | The system shall send no EF content and no EF identifier to a model for inference. | Pending |

### Operability

| ID | Requirement | Status |
|---|---|---|
| NFR-38 | The system shall emit per-region metrics for publish-to-indicator latency, fan-out backlog, dead-letter depth, backfill coverage and summary failure rate at 1 min resolution (to agree). | Pending |
| NFR-39 | If dead-letter depth stays above 0 for 15 min (to agree), then the system shall raise an alert. | Pending |

### Rollout

| ID | Requirement | Status |
|---|---|---|
| NFR-40 | The system shall make only additive changes to the existing template and EF stores. | Pending |
| NFR-41 | Where the feature flag is disabled for a firm, the system shall show that firm no pending-update indicator or summary. | Pending |
| NFR-42 | When the feature flag is disabled, the system shall restore pre-feature behaviour without data migration. | Pending |
| NFR-43 | The system shall deploy without a maintenance window. | Pending |
