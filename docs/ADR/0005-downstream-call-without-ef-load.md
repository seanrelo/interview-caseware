# 0005 Downstream call without EF load

Status: Proposed (owner and latency of the endpoint are an open question for the user)
Date: 2026-10-30

## Context

Hard constraints (CLAUDE.md): loading an EF takes ~1 min, is owned by another team with limited capacity; no EF load on a read path or per publish (NFR-21, NFR-22); residency (NFR-35); failure never recorded as "no update"; additive, flagged rollout (NFR-40..43).

Index assumption 4: the fan-out worker's downstream call (FR-16) is the ~1 min per-EF call, which conflicts with NFR-22 if it is an EF load. Scenario: a publish arrives; ~20,000 EFs must show "update pending" within 10 s p95 (NFR-13), with 40 products publishing within the hour (NFR-25), and downstream capacity respected (NFR-8).

Arithmetic: 40 publishes x 20,000 EFs = 800,000 loads x 1 min = 13,333 h. Even at 40 concurrent loads that is ~14 days, versus a one-hour window (NFR-25). Impossible.

## Options considered

A. The downstream call writes a pending-update marker into the regional status store; no EF load. FR-14 finds EFs through an index on template-version records by template ID. Pros: meets NFR-22 and NFR-13; the 1-min load exists only in backfill (FR-6). Cons: needs a defined endpoint/contract with the owning team; EFs without a record are unknown until backfill (FR-24).

B. Fan-out touches only EFs with unknown versions (loads them at publish). Pros: smaller than all EFs. Cons: still loads EFs at publish (violates NFR-22), unbounded by backlog of unknown EFs, 1 min each. Rejected.

C. Load every affected EF at publish. Cons: 13,333 h as above. Rejected.

## Decision

Option A. The downstream call is a write: "record pending update (EF, template, target version)" into the regional status store. It does not load the EF. FR-14 resolves affected EFs by querying the template-version record index by template ID (partitioned per region). The only EF loads are backfill (FR-6, NFR-4, NFR-5), limited to the concurrency agreed with the owning team. EFs without a record stay unknown (FR-24), never up to date.

Open question for the user: who owns the marker endpoint (EF management system or a new regional service), and what is its latency and capacity? Until agreed, the in-flight limit stays configurable (NFR-8, to agree). Fan-out rate follows from that latency: for the NFR-13 target, ~2,000 calls/s (ADR 0007).

## Tradeoffs and consequences

- Better: NFR-22, NFR-21, NFR-13, NFR-25 feasible; downstream load becomes writes.
- Worse: depends on hook and backfill coverage; unknown share is visible until NFR-5 (14 days, to agree) is met.
- Arithmetic: 800,000 marker writes/week vs 800,000 loads x 1 min; 40 concurrent loads would be needed for 14 days of backfill only once.
- Left out: applying template content to the EF (out of scope).

## Requirements

FR-14, FR-16, FR-6, FR-24, NFR-8, NFR-13, NFR-21, NFR-22, NFR-25, NFR-4, NFR-5.
