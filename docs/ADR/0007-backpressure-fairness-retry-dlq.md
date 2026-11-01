# 0007 Backpressure, fairness, retry and DLQ

Status: Accepted (all limits remain "to agree")
Date: 2026-10-30

## Context

Hard constraints (CLAUDE.md): downstream capacity is limited and owned by another team; no EF load per publish (NFR-22); failure is never recorded as "no update"; unreliable events with DLQ; additive, flagged rollout.

Scenarios: the downstream throttles or its errors exceed 10% over 1 min (NFR-9); one large firm (~40,000 EFs, 5% of all) floods the pipe (NFR-11); a call keeps failing (FR-18, FR-19). Target: indicator in 10 s p95 (NFR-13).

Arithmetic: 20,000 EFs in 10 s needs ~2,000 calls/s. In-flight needed = rate x latency (Little); latency is unknown (to agree, ADR 0005). A firm with 40,000 EFs on a hard 25% cap gets 500 calls/s: 40,000 / 500 = 80 s, which cannot meet NFR-13 if that firm's EFs are to appear in 10 s.

## Options considered

A. Fixed concurrency limit and fixed per-firm 25% cap. Pros: simple. Cons: wrong limit either wastes capacity or overloads; a hard cap starves a large firm even when others are idle.

B. AIMD in-flight limit plus a work-conserving per-firm cap. Pros: adapts to downstream health (NFR-9); fairness only when contended. Cons: more state, tuning.

C. Token bucket on rate only. Cons: ignores latency growth; in-flight can balloon.

## Decision

Option B. The global in-flight limit starts at the agreed maximum (NFR-8, to agree). Additive increase while healthy; multiplicative decrease (halve) on a throttle signal or an error rate above 10% over 1 min (NFR-9, to agree). The per-firm cap of 25% (NFR-11, to agree) applies only while other firms have queued work; with no contention a firm may use the whole limit (work-conserving), so the 40,000-EF firm is not held to 500 calls/s when alone. Retry: exponential backoff with full jitter, 5 attempts (FR-18, to agree). After the last attempt the item goes to the DLQ and the EF status is set to unknown, never up to date (FR-19, FR-25). DLQ retained 14 days (NFR-29, to agree); alert if depth > 0 for 15 min (NFR-39, to agree). After an outage the backlog drains at the same limits without loss (NFR-32).

## Tradeoffs and consequences

- Better: downstream protection, fairness under contention, no silent failure.
- Worse: NFR-11 and NFR-13 conflict for a large firm under contention: that firm may take longer than 10 s; state this at design review. Throughput drops after a cut, delaying other firms.
- Left out: per-product priority, adaptive per-firm weights.

## Requirements

FR-18, FR-19, FR-25, NFR-8, NFR-9, NFR-11, NFR-13, NFR-29, NFR-32, NFR-39.
