// SAMPLE DATA ONLY: invented firm, EF and template names. No real data.
window.MOCK = {
  firm: "Northwind Audit LLP",
  now: "2026-10-30 10:00",
  user: "j.doe",
  pageSize: 8, // sample page size; a real firm can have 40,000 EFs (NFR-14, NFR-24)
  // Template registry (global layer): version graph per PT (spec 02, FR-8).
  // Each version has parent version(s), a market branch and a status (withdrawn or not).
  templates: {
    "Audit Standard": {
      versions: [
        { n: 1, parents: [],  branch: "Global" },
        { n: 2, parents: [1], branch: "Global" },
        { n: 3, parents: [2], branch: "Global" },
        { n: 4, parents: [3], branch: "Global" },
        { n: 5, parents: [4], branch: "Global" },
        { n: 6, parents: [5], branch: "Global" },
        { n: 7, parents: [6], branch: "Global", withdrawn: true },
        { n: 8, parents: [4], branch: "CA" },   // Canada branch forked from v4, does not descend from v6
        { n: 9, parents: [6], branch: "EU" }    // EU branch; withdrawable via the toolbar (FR-13)
      ]
    },
    "Review Engagement": {
      versions: [
        { n: 1, parents: [],  branch: "Global" },
        { n: 2, parents: [1], branch: "Global" },
        { n: 3, parents: [2], branch: "Global" }
      ]
    }
  },
  // EFs. record=false: no template-version record (FR-24). lookupFailed: status could not be determined (FR-25).
  // deadLettered: fan-out retries exhausted (FR-19), shown to users only as unknown.
  // declines/accepts: (EF, version) records.
  efs: [
    { id: "EF-1001", name: "Acme Holdings FY25",     template: "Audit Standard",    branch: "Global", baseline: 6, record: true },
    { id: "EF-1002", name: "Acme Subsidiary FY25",   template: "Audit Standard",    branch: "Global", baseline: 5, record: true },
    { id: "EF-1003", name: "Birch Retail FY25",      template: "Audit Standard",    branch: "Global", baseline: 3, record: true },
    { id: "EF-1004", name: "Cedar Logistics FY25",   template: "Audit Standard",    branch: "Global", baseline: 4, record: true,
      declines: [{ version: 6, user: "a.smith", time: "2026-10-12 14:05" }] },
    { id: "EF-1005", name: "Delta Mining FY25",      template: "Audit Standard",    branch: "Global", baseline: 4, record: true,
      declines: [{ version: 5, user: "a.smith", time: "2026-09-02 09:30" }] },
    { id: "EF-1006", name: "Elm Partners FY24",      template: null,                branch: null,     baseline: null, record: false },
    { id: "EF-1007", name: "Fir Group FY25",         template: "Audit Standard",    branch: "Global", baseline: 5, record: true, lookupFailed: true },
    { id: "EF-1008", name: "Gum Tree Estates FY25",  template: "Audit Standard",    branch: "CA",     baseline: 6, record: true },
    { id: "EF-1009", name: "Hazel Co review FY25",   template: "Review Engagement", branch: "Global", baseline: 2, record: true },
    { id: "EF-1010", name: "Ivy Corp review FY25",   template: "Review Engagement", branch: "Global", baseline: 3, record: true },
    { id: "EF-1014", name: "Kiwi Foods FY25",        template: "Audit Standard",    branch: "EU",     baseline: 6, record: true },
    { id: "EF-1015", name: "Larch Foods FY25",       template: "Audit Standard",    branch: "Global", baseline: 5, record: true, deadLettered: true }
  ]
};
