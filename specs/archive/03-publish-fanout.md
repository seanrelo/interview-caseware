# Publish fan-out

Back to [index](00-index.md). Depends on: [02-template-version-graph](02-template-version-graph.md).

Status: Pending

Purpose: Turn one publish event into per-EF work and process it within the capacity of the downstream EF system (Part 2 of the exercise).

## Functional requirements

| ID | Requirement | Status |
|---|---|---|
| FR-14 | When the fan-out worker receives a publish event, the fan-out worker shall identify the EFs in each region whose template ID matches the event. | Pending |
| FR-15 | The fan-out worker shall create exactly one work item per affected EF, keyed by (EF, template ID, target version). | Pending |
| FR-16 | When the fan-out worker processes a work item, the fan-out worker shall invoke the downstream call that records the pending update for that EF. | Pending |
| FR-17 | If the fan-out worker receives a work item whose key it has already processed, then the fan-out worker shall leave the EF's state unchanged. | Pending |
| FR-18 | If a downstream call fails, then the fan-out worker shall retry the work item with exponential backoff, up to 5 attempts (to agree). | Pending |
| FR-19 | If a work item exhausts its retries, then the fan-out worker shall move it to the dead-letter queue and set the EF's pending-update status to unknown. | Pending |
| FR-20 | When a newer eligible version for the same template ID exists, the fan-out worker shall skip unprocessed work items that target an older version. | Pending |
| FR-21 | When every work item of a publish event is complete, the fan-out worker shall record the publish event as fully processed. | Pending |

## Non-functional requirements

| ID | Requirement | Status |
|---|---|---|
| NFR-8 | The fan-out worker shall keep in-flight downstream calls at or below the limit agreed with the owning team (limit to agree). | Pending |
| NFR-9 | If the downstream signals throttling or its error rate exceeds 10% over 1 min (to agree), then the fan-out worker shall reduce its in-flight call limit. | Pending |
| NFR-10 | When the fan-out worker restarts, the fan-out worker shall lose no pending work item and re-invoke no completed work item. | Pending |
| NFR-11 | While a fan-out is running, the fan-out worker shall keep any single firm's share of in-flight downstream calls at or below 25% (to agree). | Pending |
| NFR-12 | The fan-out worker shall produce the same final EF states regardless of the order in which duplicate or out-of-order publish events arrive. | Pending |
