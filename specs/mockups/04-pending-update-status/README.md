# Mockup: 04 Pending update status

Source spec: [04-pending-update-status](../../04-pending-update-status.md). Also read: [00-index](../../00-index.md) (terms, assumptions), [02-template-version-graph](../../archive/02-template-version-graph.md) (FR-11 reachability, FR-12 no-path unknown, FR-13 withdrawal), [03-publish-fanout](../../archive/03-publish-fanout.md) (FR-19 dead-lettered), [05-summary-generation](../../05-summary-generation.md) (only to mark where the summary goes).

All data is invented sample data (`data.js`). No network, no persistence; Reset restores it.

## Open

Open `index.html` in a browser (works from disk). Toggle **Show requirements** to outline each tagged element with its requirement IDs.

## Screens

| Screen | What | FRs |
|---|---|---|
| List | One row per EF with one status badge, filter by status with counts, paged (previous/next) | FR-22..FR-26, FR-28, FR-29, NFR-14, NFR-24 |
| Detail dialog ("Review update" / "Decision history") | Baseline to latest eligible version, accept, decline, decision log | FR-4, FR-26, FR-27, FR-29, FR-31, FR-32 |

## State matrix

| State | Shows | How to reach |
|---|---|---|
| Up to date | FR-22 | Acme Holdings, Ivy Corp |
| Update pending | FR-23 | Acme Subsidiary, Hazel Co |
| Pending, accumulated | FR-26 (3 versions in the cumulative range shown as one, v3 to v6) | Birch Retail |
| Declined, no newer version | FR-28, FR-29 (up to date, baseline unchanged) | Cedar Logistics |
| Declined v5, later v6 eligible | FR-30 (pending from baseline v4 to v6) | Delta Mining |
| Unknown, no record | FR-24 | Elm Partners |
| Unknown, lookup failed | FR-25 | Fir Group, or State = "Status lookup failed" (all EFs) |
| Unknown, no path | FR-22 (unknown) via spec 02 FR-12 (CA branch, baseline v6 is not an ancestor of the CA version v8) | Gum Tree Estates |
| Unknown, fan-out retries exhausted (dead-lettered) | FR-19; users see only "unknown" | Larch Foods |
| Pending version withdrawn | FR-13: pending v9 (EU) is withdrawn, status re-evaluated | Kiwi Foods, then "Simulate: withdraw Audit Standard v9 (EU)" |
| Paged list | NFR-14, NFR-24 (list page only) | Previous / Next under the list (8 EFs per page) |
| Feature disabled for the firm | NFR-41, NFR-42: nothing is shown in the product frame; a "Mockup note" annotation outside the frame explains the state | State = "Feature disabled for this firm" |
| Loading | NFR-14 (visible state only) | State = "Loading statuses" |
| Accept / decline in progress | FR-32 (refreshing, then status re-derived) | Review update, then Accept or Decline |
| New version published | FR-23 re-derivation (NFR-13 visible outcome) | "Simulate: publish next version" |

## Coverage

| ID | Where |
|---|---|
| FR-19 | Larch Foods shows plain unknown (cause not exposed to the user) |
| FR-22 | Status badge on every row and in detail; filter bar |
| FR-23 | Pending badge; Simulate publish |
| FR-24 | Unknown badge with "No template-version record" |
| FR-25 | Unknown badge with failure text; Fir Group; failed state |
| FR-4, FR-31 | Accept button (FR-4 and FR-31 tags) |
| FR-26 | "Update vX to vY (N versions in the cumulative range, shown as one)"; detail |
| FR-27 | Decline button; decision log (user, version, time) |
| FR-28 | Declined version not shown as pending |
| FR-29 | Baseline unchanged after decline |
| FR-30 | Delta Mining; or decline v6 on Birch, then Simulate publish |
| FR-31 | Accept button; log; baseline becomes the accepted version |
| FR-32 | "Refreshing status..." after a decision |
| NFR-13 | Not mocked: timing target; only a note |
| NFR-14, NFR-24 | Loading state and a paged list; the 1 s target and 40,000-EF scale are not simulated |
| FR-11 | Eligibility computed along parent links within the EF's market branch (not numeric order) |
| FR-12 | Gum Tree Estates (no path in the CA branch) |
| FR-13 | Kiwi Foods plus the withdraw control |
| NFR-33 | Not mockable: decision-recording failure is an open question, deliberately not mocked |

Not mocked: other spec 02/03 logic (publish events, registry storage, fan-out), spec 05 requirements (summary shown as a TBD placeholder in the detail dialog), NFR-33.

## Assumptions made while mocking

- The decision timestamp is a fixed sample value from `data.js`, not the real clock.
- A user with no EF-level permissions model: any user can accept or decline (the spec states no authorisation rules).
- Decline needs no reason; the spec asks for none.
- "Simulate: publish next version" is a mockup control, not a UI the spec requires.
- Eligibility picks the highest version number among reachable versions in the branch as "latest"; the spec does not define how "latest" is ordered in a graph.
- The withdraw and publish controls are mockup controls, not UI the spec requires.
