// SAMPLE DATA ONLY: invented firm, EF, template, model and diff content. No real data.
window.MOCK = {
  firm: "Northwind Audit LLP",
  now: "2026-10-30 10:00",
  model: "sample-model-2026-01-15",   // pinned identifier, no alias (NFR-17)
  promptVersion: "summary-prompt v3",
  // Pending updates (status stays "update pending" in every summary state, FR-42).
  efs: [
    { id: "EF-1002", name: "Acme Subsidiary FY25", template: "Audit Standard",    from: 5, to: 6, note: "Summary stored, reused" },
    { id: "EF-1011", name: "Acme Parent FY25",     template: "Audit Standard",    from: 5, to: 6, note: "Same pair as Acme Subsidiary: same stored summary (FR-36)" },
    { id: "EF-1003", name: "Birch Retail FY25",    template: "Audit Standard",    from: 3, to: 6, note: "Accumulated: v3 to v6 as one" },
    { id: "EF-1009", name: "Hazel Co review FY25", template: "Review Engagement", from: 2, to: 3, note: "No summary yet: generated on open (FR-34)" },
    { id: "EF-1012", name: "Juniper Ltd FY25",     template: "Audit Standard",    from: 4, to: 6, fail: "failed",   note: "Sample: generation failed" },
    { id: "EF-1013", name: "Kelp Bank FY25",       template: "Audit Standard",    from: 1, to: 6, fail: "rejected", note: "Sample: rejected, a statement had no linked diff item (FR-41)" }
  ],
  // Stored summaries keyed by "template:from-to". Immutable once stored (NFR-15).
  summaries: {
    "Audit Standard:5-6": {
      id: "S-0001", generated: "2026-10-28 06:02",
      diff: [
        { id: "D1", section: "Revenue recognition", kind: "Changed", text: "Procedure 3.2 now requires a second reviewer sign-off." },
        { id: "D2", section: "Going concern", kind: "Added", text: "New checklist item on post-year-end events." }
      ],
      statements: [
        { text: "Revenue recognition: procedure 3.2 now needs a second reviewer to sign off.", refs: ["D1"] },
        { text: "Going concern: a new checklist item covers events after the year end.", refs: ["D2"] }
      ]
    },
    "Audit Standard:3-6": {
      id: "S-0002", generated: "2026-10-28 06:04",
      diff: [
        { id: "D1", section: "Materiality", kind: "Changed", text: "Default benchmark changed from total revenue to profit before tax." },
        { id: "D2", section: "Revenue recognition", kind: "Changed", text: "Procedure 3.2 now requires a second reviewer sign-off." },
        { id: "D3", section: "Going concern", kind: "Added", text: "New checklist item on post-year-end events." },
        { id: "D4", section: "Related parties", kind: "Removed", text: "Legacy disclosure worksheet removed." },
        { id: "D5", section: "Sampling", kind: "Changed", text: "Minimum sample size raised from 25 to 40." }
      ],
      statements: [
        { text: "Materiality: the default benchmark is now profit before tax instead of total revenue.", refs: ["D1"] },
        { text: "Revenue recognition: procedure 3.2 needs a second reviewer sign-off.", refs: ["D2"] },
        { text: "Going concern: a new checklist item covers events after the year end.", refs: ["D3"] },
        { text: "Related parties: the legacy disclosure worksheet is removed.", refs: ["D4"] },
        { text: "Sampling: the minimum sample size rises from 25 to 40.", refs: ["D5"] }
      ]
    }
  },
  // Summaries generated when first opened (FR-34); stored here after the mock generation delay.
  generatable: {
    "Review Engagement:2-3": {
      id: "S-0003", generated: "2026-10-30 10:00",
      diff: [
        { id: "D1", section: "Inquiries", kind: "Added", text: "New inquiry template for management representations." },
        { id: "D2", section: "Analytical procedures", kind: "Changed", text: "Threshold for follow-up lowered from 10% to 5%." }
      ],
      statements: [
        { text: "Inquiries: a new template covers management representations.", refs: ["D1"] },
        { text: "Analytical procedures: follow-up is triggered at 5% instead of 10%.", refs: ["D2"] }
      ]
    }
  },
  // Generation outcome after "Simulate: retry succeeds" for failed/rejected pairs.
  retryResult: {
    "Audit Standard:4-6": {
      id: "S-0004", generated: "2026-10-30 10:00",
      diff: [{ id: "D1", section: "Sampling", kind: "Changed", text: "Minimum sample size raised from 25 to 40." }],
      statements: [{ text: "Sampling: the minimum sample size rises from 25 to 40.", refs: ["D1"] }]
    },
    "Audit Standard:1-6": {
      id: "S-0005", generated: "2026-10-30 10:00",
      diff: [{ id: "D1", section: "Materiality", kind: "Changed", text: "Default benchmark changed from total revenue to profit before tax." }],
      statements: [{ text: "Materiality: the default benchmark is now profit before tax instead of total revenue.", refs: ["D1"] }]
    }
  }
};
