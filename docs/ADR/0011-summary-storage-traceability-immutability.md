# 0011 Summary storage, traceability and immutability

- Status: Accepted
- Date: 2026-10-30

## Context

Hard constraints (CLAUDE.md): summaries may be reviewed in a regulatory audit months later, so they are immutable and reproducible: store diff, model, prompt version and generation time, with each statement traceable to a diff item. Generate once per pair, shared across firms. The global layer holds no EF content (NFR-36). Additive rollout (NFR-40).

Scenarios:
- An auditor asks what a user saw. The text, diff, model ID, prompt version and generation time are returned for at least 7 years (to agree) (NFR-16, FR-40).
- A model emits a statement with no source. The summary is rejected (FR-41).
- Someone tries to edit or regenerate. The stored record is unchanged; a new record is created (NFR-15).

## Options considered

1. **Mutable DynamoDB item, overwritten on regeneration.** Pros: simple. Cons: history lost; violates NFR-15.
2. **Relational table with audit trigger.** Pros: queryable. Cons: new database in the global layer; immutability by convention, not enforced.
3. **S3 with Object Lock (compliance mode) for the record plus a DynamoDB index and current pointer.** Pros: immutability enforced by storage, cheap long retention, versioned regeneration. Cons: two stores to keep consistent; Object Lock retention is hard to shorten.

## Decision

Option 3. The model must return structured output: a list of statements, each with `text` and `diffItemIds[]` (at least one). A validator checks that every statement has at least one ID, that every ID exists in the stored diff, and that the text has no raw JSON paths (FR-38 applies to the summary text, not to the stored diff, which keeps paths); otherwise reject (FR-41, FR-42). A passing summary is written as one S3 object `summaries/{templateId}/{baseline}/{target}/{generationId}.json` containing summary text and statements, the diff, model ID, prompt version and generation time (FR-39, FR-40), under Object Lock compliance mode with 7-year retention (to agree; assumption 7). DynamoDB holds the index (pair key to current generationId, S3 key, hash) and is the only mutable item, and only the pointer changes. Regeneration writes a new object and moves the pointer; old objects stay retrievable (NFR-15). Write order: object first, then the conditional pointer update; an orphan object is harmless and found by reconciliation. Store a content hash in the index to detect tampering on read.

## Tradeoffs and consequences

- Better: auditability and immutability enforced by the platform (NFR-15, NFR-16); sharing across firms. Worse: wrong summaries cannot be deleted before retention ends; mitigate with the pointer (supersede, not erase) and a "withdrawn" flag in the index; a legal-erasure need would contradict compliance mode (to agree).
- Arithmetic: objects = distinct pairs = 40 x B per week (B unset, ADR 0010), stored once for 4,000 firms rather than 4,000 times. Size is dominated by the diff; no figure given.
- Residency: record holds only version content, diffs and text (NFR-36, NFR-37). Region of the global layer is to agree.
- Left out: signing records with a KMS key (candidate hardening); full-text search of summaries.

## Clarification 2026-11-01 (user-approved, no decision changed)

5. "Raw JSON path" (FR-38): a statement is rejected if its text contains any stored diff item path string, or matches a path pattern such as `$.a.b`, `/a/b`, or dotted/bracket notation `a.b[0]`. Section names come from the diff item's sectionName.
6. Tamper check: a SHA-256 over canonical content is stored with each record. A mismatch on read raises an error and the view shows "unavailable", never a summary. The store has no update or delete method, only append and the current-pointer move (NFR-15). Retention (7 years) is an object-lock concern of the real store and is not testable in-memory (NFR-16).

## Requirements

FR-37, FR-38, FR-39, FR-40, FR-41, NFR-15, NFR-16, NFR-17 (model ID stored), NFR-36.
