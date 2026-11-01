# Mockup: 05 Summary generation

Source spec: [05-summary-generation](../../05-summary-generation.md). Also read: [00-index](../../00-index.md), [04-pending-update-status](../../04-pending-update-status.md) (the update stays "update pending" in every summary state), [02-template-version-graph](../../archive/02-template-version-graph.md).

All data is invented sample data (`data.js`, including the model identifier). No network, no persistence; Reset restores it.

## Open

Open `index.html` from disk. Choose "Open update" on a row. "Show requirements" outlines tagged elements with their IDs.

## Screens

One screen: list of pending updates (left) and the summary panel (right; stacked under 720 px).

## State matrix

| State | Shows | How to reach |
|---|---|---|
| Summary ready, AI-generated | FR-33, FR-38, FR-39, FR-43 | Acme Subsidiary |
| Same pair reused by another EF | FR-36 (same record S-0001; shown within one firm only, other firms are not visible) | Acme Parent |
| Accumulated, cumulative | FR-33 (v3 to v6, one summary) | Birch Retail |
| Generated on open | FR-34 (spinner, then ready) | Hazel Co |
| Summary unavailable, still pending, retry scheduled | FR-42 | Juniper Ltd |
| Rejected summary (no linked diff item) | FR-41, shown to the user as FR-42 | Kelp Bank |
| Retry succeeds | FR-42 follow-up | Toolbar "Simulate: retry succeeds" |
| Feature disabled for the firm | NFR-41, NFR-42: product frame empty; a "Mockup note" annotation outside the frame explains it | State = "Feature disabled for this firm" |

## Coverage

| ID | Where |
|---|---|
| FR-33 | Baseline to latest, summary on open |
| FR-34 | Hazel Co generating state |
| FR-36 | Same record id for Acme rows; "stored record, reused" in audit details. Shown within one firm only: reuse across firms cannot be shown without revealing other firms |
| FR-37, FR-38 | Diff items in audit details; statements use section names, no JSON paths |
| FR-39 | Statement markers [D1]; clicking one opens Audit details and focuses the diff item |
| FR-40, NFR-16, NFR-17 | Audit details: model, prompt version, generation time |
| FR-41, FR-42 | Kelp Bank / Juniper: summary-unavailable message, status stays pending |
| FR-43 | "AI-generated summary" label |
| NFR-15 | Record id shown as immutable |
| NFR-41 | Feature-disabled state |
| FR-35 | Not mocked: backend generation on publish |
| NFR-18, NFR-19, NFR-20 | Not mocked: release gates and timing targets |

## Assumptions and TBD

- The "Audit details" disclosure is my addition to make FR-39/FR-40/NFR-16 visible. Who may see it is not specified; the UI shows a TBD note.
- Rejected and failed generations look the same to users (FR-42 gives one message).
- Generation delay (1.5 s) is a mock value.
- Every (baseline, target) pair in the sample data resolves to a summary, except the deliberate failure rows (Juniper, Kelp) until the retry. The generating state has no timeout because the spec states none (open question).
