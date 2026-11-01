# Test matrix: spec 03 publish fan-out

Source: `specs/archive/03-publish-fanout.md`, plan `specs/plan/03-publish-fanout-plan.md` sections 3 and 6, ADR 0006 (incl. clarification), ADR 0007.
Run: `./mvnw -q test` (read the Maven summary line; with jqwik on the classpath the per-class surefire XML under-reports, the console summary is authoritative).
Status legend: PASS = test exists and passes in the last run; FAIL = test exists and exposes a defect (see "Defects"); N/T = not testable here.
Rules for all tests: fake `Clock`, no `Thread.sleep`, seeded `Random`, jqwik seed printed by jqwik; `PublishProgressStore.addAndSeal` only.
jqwik pitfall (found in T8): a `@Property` method must not carry JUnit annotations such as `@DisplayName`; jqwik then skips it silently (surefire shows "skipped"). Use `@Label`. Verified executed: console shows `tries = 300 / checks = 300` for both P-12 properties and `tries = 500` for C-8.
Fault decorators (self-tested in `fault/FaultWrappersTest`): `FlakyMarker`, `CrashingStore`, `SlowIndex`. System wiring helper: `system/Rig`.
Test class abbreviations: PH = `app/PublishHandlerTest`, IP = `app/ItemProcessorTest`, RFH = `app/RetryingFailureHandlerTest`, SC = `app/SupersessionAndCompletionTest`, AL = `limiter/AdaptiveLimiterTest`, FD = `limiter/FairnessAndDrainTest`, RP = `limiter/RetryPolicyTest`, OI = `system/OrderIndependenceTest`, CR = `system/CrashRecoveryTest`, PC = `system/PublishToCompletionTest`, CL = `system/ConcurrencyAndLeaseTest`, CG = `system/CoverageGapsTest`, LT = `system/LoadTest`, RT = `system/RegressionTest` (formerly DefectProbeTest).

## Functional requirements

| Req | Case | Tests | Status |
|---|---|---|---|
| FR-14 | U-14 paging, empty, other template excluded, region isolation | PH.pagesThroughAllEfs, indexPagesAreBounded, exactPageMultiple, emptyIndex, otherTemplateExcluded, regionIsolation | PASS |
| FR-14 | index fails mid-scan, replay loses nothing | CG.indexFailureMidScanThenReplay (uses `SlowIndex.failOnCall(2)`), PH.retryOfUnsealedPublishCountsExistingItems | PASS |
| FR-15 | U-15 duplicate publish creates 0 new; concurrent handlers | PH.duplicatePublishCreatesNoNewItems, differentVersionIsDifferentItem, concurrentDeliveries; InMemoryWorkItemStoreTest.createIfAbsent; OI.concurrentPublishesOfDifferentVersions | PASS |
| FR-16 | U-16 marker called once per item; no EF-load method on any port | IP.markerCalledOncePerKey; PC.happyPathWithSupersessionAndFlagOff (callsFor==1); CG.noPortCanLoadAnEf | PASS |
| FR-17 | I-17 replayed key leaves state unchanged; lost ack | IP.replayedKeyLeavesStateEqual, unchangedIsSuccess, crashAfterMarkerBeforeComplete; CG.lostAckRetryIsIdempotent (`loseAckForNext(1)`: 2 calls, final state equal, DONE once) | PASS |
| FR-18 | U-18 attempts, delays, notBefore | RFH.retriesWithBackoff, fiveAttemptsThenDeadLetter, notRetriedEarly, throttleRetriedLikeErrors; RP.defaultAttempts, ceilingDoublesAndCaps, fullJitterWithinBounds, validation | PASS |
| FR-19 | F-19 always-failing item (unit) | RFH.deadLetterAndUnknown, othersUnaffected, throttleExhausts, unknownIsMonotonic | PASS |
| FR-19 | F-19 end to end publish -> dispatch -> DLQ -> tracker; throttle-exhausted variant | PC.alwaysFailingItemEndsInDlqAndUnknown (5 calls, DLQ 1, unknown, dead-lettered reported separately, not clean), PC.throttleExhaustedVariant | PASS |
| FR-19 | F-poison: 1 poison among 1,000 | PC.onePoisonAmongThousand | PASS |
| FR-20 | U-20 older skipped, race, withdrawn latest, graph order | SC.olderSkippedNewerProcessed, latestItself, raceBetweenLeaseAndExecute, raceLatestWithdrawn, unknownVersionFailsOpen; InMemoryVersionGraphReaderTest.tieBreak; OI.explicitSequences (tie branch v6a/v6b); PC.happyPathWithSupersessionAndFlagOff | PASS |
| FR-20 | withdrawn version must never become the pending target | RT.withdrawnVersionIsNeverRecorded, SC.raceLatestWithdrawn (SKIPPED/SUPERSEDED, no marker call), InMemoryVersionGraphReaderTest.eligibility | PASS (DEFECT-2 fixed) |
| FR-21 | U-21 completion arithmetic, dead letters separate, not complete before seal, redelivery after seal | SC.completionWithoutDeadLetters, completionWithDeadLetters, notCompleteWhileRetrying, replayDoesNotDoubleCount, sealedTotalNotRecounted; InMemoryPublishProgressStoreTest.addAndSealOnce, completion; PH.redeliveryAfterSealIsNoOp; PC.happyPathWithSupersessionAndFlagOff (flag-off counted as skipped) | PASS |
| FR-21 | publish completes even after a crash between DONE CAS and progress count | RT.publishCompletesAfterCrashBetweenCompleteAndCount; InMemoryWorkItemStoreTest.countByPublish | PASS (DEFECT-1 fixed) |

## Non-functional requirements

| Req | Case | Tests | Status |
|---|---|---|---|
| NFR-8 | C-8 in-flight never above cap | AL.c8_inFlightNeverAboveLimitUnderManyThreads, c8_refusesWhenAtLimitAndGrantsAgainAfterRelease, lowerLimitBlocksNewPermitsWhileExcessDrains; FD.c8_realDispatchersNeverExceedLimitDownstream; CG.limiterNeverExceedsCap (jqwik, 500 tries); IP.gateSeam; PC.* (`maxObservedInFlight <= max`) | PASS |
| NFR-9 | F-9 throttle shrinks then recovers; error rate >10% over 1 min | AL.f9_throttlingHalvesLimitThenItRecovers, f9_errorRateAboveTenPercentOverOneMinuteShrinks, f9_errorRateAtOrBelowThresholdOrOutsideWindowDoesNotShrink; PC.throttleExhaustedVariant | PASS |
| NFR-10 | F-10 crash mid-batch, restart (k = 0..20 writes, plain and applyThenCrash; leaseReady counts as a write) | CR.crashThenRestart (42 parameterised cases: every key DONE, DONE-at-crash items called exactly once, at most one re-invoked item, `totalCalls == N + reinvoked`), CR.repeatedCrashes, IP.restartLosesNothing, crashAfterMarkerBeforeComplete, expiredLeaseReLeased | PASS |
| NFR-10 | F-concurrent: N workers, no key in two marker calls at once, `delegatedFor==1`; lease expiry reclaim | CL.eightWorkersNoKeyInTwoCallsAtOnce, CL.leaseExpiryReclaimWithRealThreads; IP.concurrentWorkersNoDoubleProcessing | PASS |
| NFR-10 | F-stale-lease: stale `complete` false, stale marker write UNCHANGED | CL.staleHolderIsRefusedAndItsWriteIsANoOp (results `[APPLIED, UNCHANGED]`), IP.staleHolderCannotComplete | PASS |
| NFR-11 | F-11 fairness under contention, work-conserving | FD.f11_oneFirmWithTenThousandQueuedIsCappedAtQuarterWhileOthersWait, f11_firmCapAppliesAtEveryStepAgainstTenThousandQueuedItems, f11_loneFirmUsesTheFullLimit | PASS |
| NFR-12 | P-12 permutations | see P-12 table | PASS |

### P-12 permutations (NFR-12, FR-15, FR-17, FR-20)
Model: versions v5 < v6a < v6b < v7. v6a and v6b have the same publish time, ordered only by the Q3 tie-break (the in-memory graph is a total order, so true incomparability is not representable; documented limitation). A version enters the graph when its first event is delivered.

| # | Case | Test | Status |
|---|---|---|---|
| 1 | v5, v7 | OI.explicitSequences[1] | PASS |
| 2 | v7, v5 | OI.explicitSequences[2] | PASS |
| 3 | v5, v7, v5 | OI.explicitSequences[3] | PASS |
| 4 | v7, v7 | OI.explicitSequences[4] | PASS |
| 5 | v5, v5, v7, v7 and interleavings (each run with and without draining between events, same and fresh publish ids) | OI.explicitSequences[5..7] | PASS |
| 6 | concurrent handling and dispatch of v5, v7, v7, v5 (100 iterations, 4 threads) | OI.concurrentPublishesOfDifferentVersions | PASS |
| 7 | all permutations of (v5, v6b, v7) and of (v5, v6a, v6b, v7) | OI.explicitSequences[8..37] | PASS |
| 8 | v7 withdrawn, v5 event: final = `latestEligible` | OI.withdrawnLatestFallsBack | PASS |
| 8b | delayed event of the withdrawn v7 | RT.withdrawnVersionIsNeverRecorded, SC.raceLatestWithdrawn (SKIPPED/SUPERSEDED, no marker call), InMemoryVersionGraphReaderTest.eligibility | PASS (DEFECT-2 fixed) |
| 9 | stale dead letter (v5) does not overwrite newer v7; v7 after v5-unknown ends v7 pending | OI.staleDeadLetterDoesNotOverwriteNewerTarget | PASS |
| 10 | random permutation + duplicates + drain points == canonical (jqwik, 300 tries) | OI.anyPermutationWithDuplicatesEqualsCanonical | PASS |
| 10b | same with poison keys: dead letters order independent, never "up to date" (jqwik, 300 tries) | OI.deadLettersAreOrderIndependent | PASS |

## Cross-cutting rows

| Req | Tests | Status |
|---|---|---|
| NFR-22 | CG.noPortCanLoadAnEf (reflection: marker port is exactly `record` + `markUnknown`, no load-like method on any port) | PASS |
| NFR-23 / NFR-25 | LT.twentyThousandItems: 20,000 items, all DONE, one write per key; prints throughput (about 2e6 items/s fan-out, 1.2e5-1.7e5 items/s dispatch, ~11 MB heap on the dev machine). No absolute target asserted (target "to agree") | PASS (throughput reported only) |
| NFR-24 | N/T here: needs real downstream and read path; limiter cap covered by NFR-8 rows | N/T |
| NFR-26 | N/T: X unset (Q14) | N/T |
| NFR-27 | PH.duplicatePublishCreatesNoNewItems, redeliveryAfterSealIsNoOp, concurrentDeliveries; IP.replayedKeyLeavesStateEqual; OI.* | PASS |
| NFR-28 | N/T here: reconciliation not implemented in this worker | N/T |
| NFR-29 | RFH.deadLetterAndUnknown, retention; InMemoryDeadLetterSinkTest.retainsEntries (14-day retention itself is real-queue config: N/T) | PASS |
| NFR-30 | IP.downstreamFailureDoesNotPropagate, failureHandlerSeam; PC.downstreamDownDoesNotBreakTheWorker (failure rate 1.0: no exception, new event still accepted, everything DLQ/unknown) | PASS |
| NFR-32 | FD.f32_outageThenDrainEndsWithEveryItemTerminalAtTheSameLimits; PC.shortOutageThenDrain (retries within budget: all DONE, none DLQ, `delegatedFor==1`, max in flight <= cap); PC.longOutageDeadLettersEverythingAndLosesNothing (retries exhausted: all DEAD_LETTERED + unknown + DLQ, distinct from lost, healing does not mark them up to date) | PASS |
| NFR-35 | PH.regionIsolation | PASS |
| NFR-36 | PH.eventHasNoEfField | PASS |
| NFR-38 | AL.u38_metersRegistered; SC.latencyTimer; RFH.gauge; PC.metersWiredEndToEnd (backlog, in-flight, limit, dlq.depth, publish.marker.latency read live values) | PASS |
| NFR-39 | RFH.gauge (dlq.depth reflects sink); alert rule itself is config: N/T | PASS (gauge) / N/T (rule) |
| NFR-40, NFR-42, NFR-43 | N/T here (deployment/migration properties). NFR-42 flag-off no-write part is under NFR-41 | N/T |
| NFR-41 | IP.flagOffSkips; PC.happyPathWithSupersessionAndFlagOff (SKIPPED(FLAG_OFF), no marker call, counted skipped, other firms processed) | PASS |

## Defects found by T8 (both fixed)

- DEFECT-1 (FR-21, NFR-10): a crash between the terminal compare-and-set and the progress increment left a publish "not complete" forever. Fixed: `CompletionTracker.status` derives done/skipped/dead-lettered from `WorkItemStore.countByPublish`; `PublishProgressStore` now holds only total and sealed (`totals`); `recordTerminal` is removed. The `TerminalListener` remains only for the `publish.marker.latency` timer. Regression: `RegressionTest.publishCompletesAfterCrashBetweenCompleteAndCount`.
- DEFECT-2 (FR-20, ADR 0004): a withdrawn/ineligible target was processed. Fixed: `VersionGraphReader.isEligible` added; `VersionSupersessionCheck` returns superseded for an ineligible target or one older than the latest eligible, and still fails open on graph errors. Regression: `RegressionTest.withdrawnVersionIsNeverRecorded`, `SupersessionAndCompletionTest.raceLatestWithdrawn`.

## Traceability
- Every FR-14..21 and NFR-8..12 has at least one passing test; FR-20 and FR-21 each also have a regression test for the fixed defect.
- Tests without a requirement: none (`SmokeTest` and fault-wrapper self-tests support the harness).
- Spec-level gaps: NFR-11 vs NFR-13 conflict for a large firm under contention is documented (ADR 0007) but untestable as one target; FR-21 "complete" relies on Q4 (dead-lettered counts as terminal); incomparable version branches cannot be represented by `InMemoryVersionGraphReader` (total order), so P-12 case 7 exercises the tie-break only.

---

# Spec 02: template version graph (implemented, run 2026-10-30)

Source: `specs/archive/02-template-version-graph.md`, plan `specs/plan/02-template-version-graph-plan.md` sections 3 and 6, ADR 0004 (Proposed), ADR 0008 and 0014 for the reverse index and flag.
Status: T2 and T3 landed and the suite was run (`./mvnw test`: Tests run: 257 after the QA fixes, Failures: 0, Errors: 0, Skipped: 0, BUILD SUCCESS). The case table below is the original design; the section "Result per case" maps each row to a real test or to NOT COVERED / N-T. Rows of the traceability table are updated.
Same rules as spec 03: fake `Clock`, no `Thread.sleep`, seeded `Random`, jqwik properties use `@Label` (not `@DisplayName`).

## Notation and decisions used by the cases

- Node: `v<N>@<t>[branch,P|W]`. `t` = publishedAt (integer ticks), `P` = published, `W` = withdrawn. Edge `a>b` = a is a parent of b. Baseline is a version id. "Branch" = the EF's market branch.
- Reachable(b) = strict descendants of b through any path. Withdrawn nodes are traversable but not selectable (ADR 0004, user-approved). The baseline itself is never the answer.
- Selection key (total order, user-approved): greater `publishedAt`, ties by greater version id. Eligible = the max-key node among published, non-withdrawn, in-branch nodes in Reachable(b). Version id comparison is on the id as a string unless the id type defines an order; the test fixes ids so both agree (use `v2`..`v9`, single digit) and one extra case (TG-4c) uses `v9` vs `v10` to pin the comparison (see open point O-2).
- Result: ELIGIBLE(v) | UP_TO_DATE | UNKNOWN. UNKNOWN only if some node in the branch has `publishedAt` greater than the baseline's and no selectable node is reachable from the baseline; UP_TO_DATE if no such later node exists. See TG-8/TG-9 for the exact cases; where the spec is silent the row says ASSUMED and lists the open point.
- Registration invariant (user-approved): a version is accepted only if every parent exists and was published strictly before it (`child.publishedAt > parent.publishedAt` for all parents); cycles and unknown parents are rejected. The invariant makes the graph acyclic by construction, so the generator and evaluator may rely on `publishedAt` as a topological order.

## Case table

Common graphs (G-n) are reused. All nodes are branch `EU-A` unless stated.

- G1 (linear + side branch): v1@1 > v2@2 > v3@3; v1 > v4@4[EU-B]; v4 > v5@5[EU-B].
- G2 (withdrawn leaf): v1@1 > v2@2 > v3@3[W].
- G3 (path through withdrawn): v1@1 > v5@5 > v6@6[W] > v7@7; v1 > v2@2.
- G4 (v7 contains declined v5): v1@1 > v5@5; v5 > v7@7; v1 > v6@6 > v7@7 (v7 has parents v5 and v6).
- G5 (incomparable leaves): v1@1 > v2@2; v1 > v3@3 (v2 and v3 are siblings, neither reaches the other).
- G6 (unreachable later): v1@1; v2@2 (no edge v1 to v2, both roots in EU-A); v3@3 child of v2.

| Case | Req / ADR | Input (graph, baseline, branch) | Expected | Notes |
|---|---|---|---|---|
| TG-1 | FR-8, ADR 0004/0001 | Register v2 with parents {v1}, branch EU-B, status P; read back | Record equals input: template ID, version, parents set, branch, status; no EF-keyed field exists on the record (reflection: no field of type EF key) | model round-trip |
| TG-1b | FR-8, ADR 0004 | (a) register child at same `publishedAt` as parent; (b) child earlier than parent; (c) unknown parent id; (d) one known + one unknown parent; (e) self-parent; (f) edge that closes a cycle (re-register v1 with parent v3 where v1>v2>v3); (g) duplicate registration of identical record; (h) re-register same id with different parents | (a)-(f) rejected with a distinct error each, graph unchanged afterwards (snapshot equality); (g) idempotent no-op; (h) rejected (immutable parents) | (f) only reachable if the API allows updating parents; if records are immutable, (f) is covered by (e)/(h). A rejected registration must not leave a partial node. Parent-after-child insertion order: registering a child before its parent is (c) |
| TG-2 | FR-9, NFR-36, ADR 0001 | Publish v3 of template T | Event has exactly two fields (template ID, version); no branch, parents, firm, EF field | reflection on the event type, same style as PH.eventHasNoEfField |
| TG-3 | FR-10, ADR 0004 | G1; withdraw v3 | v3 status W; node v3, its edge v2>v3 and its content remain; other nodes unchanged | |
| TG-4 | FR-11, ADR 0004 | G1; baseline v1, branch EU-A | ELIGIBLE(v3) (v4/v5 are EU-B, excluded) | |
| TG-4a | FR-11 | G1; baseline v1, branch EU-B | ELIGIBLE(v5) | branch filter on both sides |
| TG-4b | FR-11, NFR-6, ADR 0004 | G5; baseline v1, EU-A | ELIGIBLE(v3) (later publishedAt wins among incomparable leaves) | tie-break by time |
| TG-4c | NFR-6, ADR 0004 | siblings v9@5 and v10@5 (same publishedAt) under v1; baseline v1 | Deterministic and equal to the documented id order; assert the same answer for 100 shuffled insertion orders. Expected value fixed once O-2 is answered; until then the test asserts only invariance, not the value | equal-time tie-break |
| TG-5 | FR-11, FR-10 | G2; baseline v1 | ELIGIBLE(v2) (withdrawn leaf v3 skipped) | |
| TG-5a | FR-11 | G2 with v2 also W; baseline v1 | UP_TO_DATE (ASSUMED: reachable nodes exist but none selectable; spec silent, O-1) | |
| TG-6 | FR-11, ADR 0004 | G3; baseline v1 | ELIGIBLE(v7) (path v1>v5>v6[W]>v7; v6 traversable, not selectable) | approved assumption |
| TG-6a | FR-11 | G3; baseline v6 (EF still on a now-withdrawn version) | ELIGIBLE(v7) | baseline may itself be withdrawn |
| TG-6b | FR-11, FR-13 | G3 with v7 also W; baseline v1 | ELIGIBLE(v5) (v6 and v7 skipped) | |
| TG-7 | FR-8, FR-11, ADR 0004 | G4; baseline v1 | ELIGIBLE(v7); graph-only: v5 is an ancestor of v7 and the evaluator has no decline input | Declined pinning (EF, version) is spec 04/Q8; here only assert the graph allows two parents and v5 is Reachable(v1) and an ancestor of v7. "Declined" is N/T here |
| TG-7a | FR-11 | G4; baseline v5 | ELIGIBLE(v7) | |
| TG-8 | FR-12, ADR 0004 | G6; baseline v1, EU-A | UNKNOWN (v2, v3 exist, later, same branch, unreachable from v1) | |
| TG-8a | FR-12 | baseline id not in the graph | UNKNOWN (never UP_TO_DATE; never an exception that is swallowed as "no update") | "failure is never no update" (CLAUDE.md) |
| TG-8b | FR-12 | baseline in EU-A but the only later nodes are in EU-B | UP_TO_DATE (ASSUMED, later versions in another branch do not count, O-3) | |
| TG-8c | FR-12 | G6 but v3 is W and v2 is W; baseline v1 | UNKNOWN (ASSUMED: a later withdrawn node still counts as "a later version exists", O-1) | |
| TG-9 | FR-12 (clarification), ADR 0004 | G1; baseline v3, EU-A | UP_TO_DATE (nothing newer) | |
| TG-9a | FR-12 | Empty graph except baseline | UP_TO_DATE | |
| TG-10 | FR-13, ADR 0004/0008 | Reverse index with EFs: a1,a2 pending v3; b1 pending v2; c1 pending v3 of another template; withdraw v3 | Affected set is exactly {a1,a2}; b1 and c1 untouched | exact set, no extras |
| TG-10a | FR-13 | Index has no entries for v3 | Empty set, no error, no status write | |
| TG-10b | FR-13, NFR-35, ADR 0001 | Two cells (EU, CA) each with EFs pending v3; withdraw v3 delivered to the EU cell | Only EU EFs re-evaluated; the CA index is not read and no CA EF key appears in any EU output (and the reverse) | region isolation |
| TG-11 | FR-13, FR-11 | G2 plus EF a1 pending v3 (baseline v1); withdraw v3 | a1 re-evaluated to ELIGIBLE(v2) (status record updated); if no other eligible exists, UP_TO_DATE, not left on v3 | |
| TG-11a | FR-13, FR-12 | EF pending v3, withdraw v3, graph where no eligible remains and a later unreachable node exists | UNKNOWN | |
| TG-11b | FR-13 | Evaluation throws for one EF (injected graph read failure) among three | The failing EF ends UNKNOWN (never UP_TO_DATE), the other two are updated; failure counted/surfaced | |
| TG-12 | FR-13, NFR-21/22, ADR 0005/0008 | Run TG-11 with a loader double that fails the test on any call (reflection check: no `VersionGraph` or re-evaluation port exposes an EF-load method) | Zero loader calls; inputs used are only graph snapshot and stored baseline | |
| TG-13 | NFR-6, ADR 0004 | Property test on random DAGs (generator below), many (baseline, branch) pairs | (1) same snapshot + inputs gives same output across 100 permutations of node and edge insertion order and repeated calls; (2) result, when ELIGIBLE, is P, in branch, in Reachable(b), and no other such node has a greater key; (3) result UP_TO_DATE implies no node in the branch has publishedAt > baseline's; (4) UNKNOWN implies such a node exists and no selectable reachable node exists (per TG-5a/8c assumptions); (5) invariant I-5: after withdrawing w, for every baseline b the answer is unchanged unless it was w | |
| TG-14 | NFR-7, ADR 0004/0011 | Withdraw a version that a stored summary references | Content and diff of the withdrawn version still readable and byte-equal to before; withdrawal API has no delete path (reflection: no `delete`/`remove` method) | retention "while referenced" cannot be time-tested here |
| TG-15 | NFR-23, ADR 0004 | One graph of 40 templates x ~30 versions; evaluate 20,000 EFs in memory | All results equal a naive reference evaluator; zero I/O ports touched; print elapsed time, assert no absolute number (target "to agree") | mechanism not number |
| TG-16 | NFR-27, NFR-32, ADR 0006 | Withdraw v3 event delivered 1, 2 and 5 times, and interleaved with a duplicate publish event | Final status store equals the single-delivery state; withdrawal of an already-withdrawn version is a no-op (no extra writes, no error) | repeated withdrawal |
| TG-16a | NFR-32 | Withdraw v3 then v2, vs v2 then v3, same graph | Final statuses for all EFs equal in both orders | out-of-order |
| TG-16b | NFR-32 | Withdraw event for a version not in the registry | Rejected/dead-lettered, no status written, never UP_TO_DATE | |
| TG-17 | NFR-41, ADR 0014 | Firm F flag off, EF pending v3; withdraw v3 | No status written for F's EFs, counted as skipped (same as spec 03 SKIPPED(FLAG_OFF)); firm G (flag on) in the same event is updated | |
| TG-17a | NFR-41 | Flag flips on after the event | Next evaluation (reconciliation/replay) produces the correct status; nothing was lost | N/T if reconciliation is absent |

## Seeded random-DAG generator (for TG-13, TG-15, TG-4c)

- Location: `src/test/.../graph/RandomGraphs.java`, pure function `generate(long seed, Params)` returning a list of version records (id, publishedAt, parents, branch, status). Uses `new java.util.SplittableRandom(seed)`; no other randomness; no wall clock.
- Sizes: small (3..8 nodes, 1..2 branches) for exhaustive checks against a brute-force reference (enumerate all baselines); medium (20..60 nodes, 1..4 branches, 1..5 templates); large (40 templates x 30 versions, one fixed seed, for TG-15).
- Construction: assign `publishedAt` as strictly increasing for the topological order, with an optional "ties" mode (probability 0.2) that gives two non-related nodes the same `publishedAt`; parents of node i are 1..3 earlier nodes chosen uniformly (so child publishedAt > parent publishedAt always holds; the generator must pass its own output through the real registration check and the test asserts zero rejections). Branch: node inherits a parent's branch with p=0.8, else a different branch. Status: withdrawn with p in {0, 0.15, 0.4}. Include a "forest" mode (p=0.1 of a node with no parents) to produce unreachable cases for UNKNOWN.
- Ids: `v<k>` with k not zero-padded and up to 3 digits so that string order and numeric order disagree (v9 vs v10); the generator emits the id order the implementation is specified to use, fixed by O-2.
- Seeds: fixed regression seeds {1, 2, 3, 42, 20261030} always run, plus jqwik's own random seeds (300 tries, seed printed by jqwik). A failure must print the generator seed and Params so the case replays with `generate(seed, params)`.
- Shuffling: for each graph run the evaluator on the original and on 100 permutations of (a) node registration order, constrained to parent-before-child (a random topological order, since registration rejects unknown parents) and (b) the order of each node's parents list; plus a separate run that registers via the "dependency-free" path if the API offers batch load. All results must be equal.
- Reference evaluator in the test: brute-force BFS from the baseline, filter, max by (publishedAt, id). The test compares the real evaluator to it; the reference is deliberately written without sharing code.
- Baselines: every node of small graphs; 50 sampled for medium; the parameter set also includes a baseline id absent from the graph (expects UNKNOWN, TG-8a).

## Open points that change expected values

- O-1 RESOLVED as assumed: reachable nodes all withdrawn gives UP_TO_DATE (TG-5a); later nodes all withdrawn and unreachable gives UNKNOWN (TG-8c). Implemented that way in `ImmutableVersionGraph.eligibleVersion` (code-inspected: the unreachable-later scan does not filter on status). No test pins either row yet (see gaps).
- O-2: open in the matrix: `VersionId` is compared as a string (`VersionId::value`, `TemplateVersion.ORDER`); `InMemoryVersionGraphReaderTest.tieBreak` pins equal-time tie-break by id for `latestEligible`, not for `eligibleVersion`; v9 vs v10 now pinned as string order by VersionGraphPinningTest (TG-4c).
- O-3 RESOLVED as assumed: later = `TemplateVersion.ORDER` greater than baseline's (publishedAt, then id), in the requested branch. TG-8b (later nodes only in another branch gives UP_TO_DATE) is implemented but not pinned by a test.

## Result per case (test class: G = VersionGraphTest, W = WithdrawalReevaluatorTest, P = VersionGraphPropertyTest, R = InMemoryVersionGraphReaderTest / GraphBackedVersionGraphReaderContractTest)

| Case | Status | Test (method) |
|---|---|---|
| TG-1 | PASS (partial: no reflection check for EF-keyed field) | G.roundTrip, G.parentsImmutable |
| TG-1b | PASS (partial: a,b,c,d,e,g,h via G.invariants; (f) N-T, records immutable; distinct error per case not asserted, all IllegalArgumentException) | G.invariants |
| TG-2 | PASS | G.eventShape (record components exactly templateId, version), G.roundTrip |
| TG-3 | PASS (partial: "other nodes unchanged" not asserted) | G.withdrawKeepsNode |
| TG-4, 4a | PASS | G.branches |
| TG-4b | PASS | VersionGraphPinningTest (sibling leaves, insertion order swapped) |
| TG-4c | PASS (O-2 open: string order pinned) | VersionGraphPinningTest |
| TG-5 | PASS | G.withdrawnLeafSkipped |
| TG-5a | PASS (UP_TO_DATE as assumed) | VersionGraphPinningTest |
| TG-6 | PASS | G.pathViaWithdrawn |
| TG-6a, 6b | PASS only via the property (every node used as baseline, withdrawn included) | P.properties |
| TG-7 | PASS | G.v7ContainsV5 |
| TG-7a | PASS | G.v7ContainsV5 (baseline v5) |
| TG-8 | PASS | G.unreachableIsUnknown |
| TG-8a | PASS (throws IllegalArgumentException, never UpToDate; W.missingBaselineUnknown covers the stored-baseline-missing path) | G.unknownBaseline, W.missingBaselineUnknown |
| TG-8b | PASS (UP_TO_DATE as assumed) | VersionGraphPinningTest |
| TG-8c | PASS (UNKNOWN as assumed) | VersionGraphPinningTest |
| TG-9, 9a | PASS | G.upToDate |
| TG-10 | PASS | W.affectedSetExact |
| TG-10a | PASS | W.emptyAffected |
| TG-10b | PASS (EU run leaves CA entries untouched; "CA index not read" not asserted) | W.regionIsolation, W.affectedSetExact |
| TG-11 | PASS | W.movesToNextEligible, W.eligibleOlderThanWithdrawnAllowed, W.noneLeftClearsMarker, W.upToDateDropsIndexEntry, W.replayAfterReevaluateFailure |
| TG-11a | PASS | W.unknownWhenUnreachableLaterNode, W.missingBaselineUnknown |
| TG-11b | PASS | W.perEfFailureEndsUnknownAndLoopContinues, W.failureNeverUpToDate |
| TG-12 | PASS (weak: `FailingEfLoader` is not wired into any port, so zero calls is vacuous; the real evidence is the reflection scan for "load" in W.noLoaderCalls and CoverageGapsTest.noPortCanLoadAnEf) | W.noLoaderCalls, CoverageGapsTest.noPortCanLoadAnEf |
| TG-13 | PASS (partial: items (1),(2) and "no result implies empty candidate set" checked; (3),(4) UP_TO_DATE vs UNKNOWN classification and (5) withdraw invariant not asserted; 1 shuffle per seed not 100; no absent-baseline case; reference shares `TemplateVersion.ORDER`) | P.properties |
| TG-14 | PASS (partial: content readable after withdrawal; byte-equality and "no delete path" reflection not asserted) | G.contentSurvivesWithdrawal |
| TG-15 | PASS (partial: 20,000 evaluations on a 50-node chain, not 40x30, no naive reference; no timing assertion, as intended) | G.manyEvaluations |
| TG-16 | PASS | W.repeatedWithdrawalSameState, W.repeatIsNoOp, G.doubleWithdraw |
| TG-16a | PASS | W.outOfOrderWithdrawals |
| TG-16b | PASS | W.unknownVersionRejected |
| TG-17 | PASS | W.flagOffNoWrite |
| TG-17a | PASS | W.flagFlipsOnReplay |

Renamed test: the old design had `eligibleOlderThanWithdrawnBlocked` (eligible older than the withdrawn target was blocked by the monotonic marker). After the approved `PendingUpdateMarker.reevaluate` port change it is `W.eligibleOlderThanWithdrawnAllowed` (older eligible now lowers pending; ADR 0006 clarification). No reference to the old name remains in the repo.

Determinism property (P.properties) shuffles for real: `shuffledTopo` repeatedly picks a random element among the nodes whose parents are already registered, so the registration order differs per seed (200 seeds), and the shuffled build is compared with `isEqualTo` and per (baseline, branch) result. Caveat: `ImmutableVersionGraph` stores nodes in `TreeMap`s, so order independence holds by construction; the test would still catch a regression that made storage order-dependent.

## Traceability

| Req | Cases | Status |
|---|---|---|
| FR-8 | TG-1, 1b | PASS (G.roundTrip, G.invariants, G.parentsImmutable) |
| FR-9 | TG-2 | PASS (G.eventShape) |
| FR-10 | TG-3, TG-5 | PASS (G.withdrawKeepsNode, G.withdrawnLeafSkipped, G.doubleWithdraw) |
| FR-11 | TG-4, 4a, 5, 6, 7, 7a, 13 | PASS (incl. TG-4b, 4c, 5a) |
| FR-12 | TG-8, 8a, 9, 9a, 11a | PASS (incl. TG-8b, 8c) |
| FR-13 | TG-10, 10a, 10b, 11, 11a, 11b | PASS (W.*) |
| NFR-6 | TG-13 | PASS (P.properties, independent oracle; VersionGraphPinningTest) |
| NFR-7 | TG-3, TG-14 | PASS (partial) |
| NFR-23 | TG-15 | PASS (mechanism only) |
| NFR-27 / NFR-32 | TG-16, 16a, 16b | PASS |
| NFR-35 / NFR-36 | TG-10b, TG-2 | PASS |
| NFR-41 | TG-17, 17a | PASS |
| NFR-21 / NFR-22 | TG-12 | PASS (weak, see above) |

Coverage (updated after fixes; ./mvnw test: Tests run: 257, Failures: 0, Errors: 0, Skipped: 0): 36 TG rows; 36 PASS or N-T, none NOT COVERED. TG-1b(f) is N-T. O-2 (string vs numeric version id order) stays open.

Not testable here (reason):
- NFR-28 (stale graph cache repaired in 24 h): reconciliation is owned by plan 06 and not implemented in this worker.
- NFR-40, NFR-42, NFR-43 (additive registry attributes, withdrawal as inert flag, expand-only rolling deploy): deployment/migration properties, design review.
- NFR-35 registry replication to cells and production-size graph: infrastructure.
- NFR-7 retention duration "as long as any summary references": needs the summary store (spec 05/ADR 0011); only "no delete path, content readable" is tested.
- "Declined pins (EF, version)" in TG-7: spec 04/Q8; only the graph shape is tested.
- FR-13 trigger (how withdrawal is communicated, Q7): no event is defined; TG-10..12 call the re-evaluation entry point directly.
- Existing `InMemoryVersionGraphReader` is a total order and cannot represent incomparable leaves; TG-4b, 5, 6, 8 need the new `VersionGraph` implementation (T2), not the spec 03 reader.

---

# Spec 01: version capture and backfill (QA T5, run 2026-10-30)

Run: `./mvnw test` in fanout-worker. Real summary line: `Tests run: 319, Failures: 0, Errors: 0, Skipped: 0` (BUILD SUCCESS; measured before the probes were added). Probes in `capture/QaProbeCaptureTest.java` are expected red: with them present the suite is 322 run, 3 failures.

Test classes (package `com.caseware.fanout.capture` unless noted): T = TemplateVersionRecordStoreTest, H = CaptureHooksTest, R = CaptureRelayTest, B = BackfillJobTest, S = CaptureSystemTest, C = RecordBackedContractTest, CFG = config.FanoutConfigurationCaptureTest.

## Primary requirements

| Req | Test(s) | Status |
|---|---|---|
| FR-1 | H.create | PASS |
| FR-2 | H.open | PASS |
| FR-3 | H.save | PASS |
| FR-4 | T.acceptWritesBaselineOnly, T.acceptConditional, T.acceptNoRecord, T.acceptOtherTemplate, H.accept, B.seedingThenAccept | PASS |
| FR-5 | T.duplicate, T.outOfOrder, T.conflict, H.duplicateDelivery, H.outOfOrder, T.concurrentSameEf | PASS |
| FR-6 | B.loadsOnlyRecordLess, B.hookCoveredBetweenEnumerationAndLoad, B.hookWinsRaceAgainstLoadStart | PASS (see weak evidence W2) |
| FR-7 | B.failedThenRecovered, B.retryWaitsForBackoff, B.permanentlyFailing | PASS (D2 fixed: CaptureRegressionTest.backfillSurvivesStoreReadFailure) |
| NFR-1 | H.noLoadAnywhere | PASS but weak (W1) |
| NFR-2 | H.timingIndicative | NOT TESTABLE (prints p95, asserts only one append per call) |
| NFR-3 | H.outboxDown, H.fallbackReplaysIntoOutbox, H.neverThrows | PASS (fallback is memory only, lost on crash, documented) |
| NFR-4 | B.inFlightNeverExceedsLimit, B.limitChangesAtRuntime, B.retriesRespectLimit | PASS for the mechanism; real limit NOT TESTABLE |
| NFR-5 | B.loadBudgetArithmetic, B.coveragePerRegion | PASS for arithmetic and metric; 14-day coverage NOT TESTABLE |

## Cross-cutting rows (plan 01 section 3)

| Req | Test(s) | Status |
|---|---|---|
| NFR-22 | B.onlyBackfillLoads, S.hooksThenPublish, H.noLoadAnywhere | PASS but weak (W1): S loaderCalls is never wired to anything |
| NFR-23 | B.loadBudgetArithmetic, T.duplicate (one record per EF) | PASS (mechanism only, no 800,000 scale) |
| NFR-27 | T.duplicate/outOfOrder/concurrentSameEf, H.orderIndependent, H.duplicateDelivery | PASS |
| NFR-28 | none | NOT TESTABLE here (reconciliation is plan 06; `scan` exists, nothing consumes it) |
| NFR-29 | R.deadLetterAfterNAttempts, R.failingSinkKeepsMessage | PASS for DLQ; 14-day retention NOT TESTABLE |
| NFR-30 | R.retryAfterStoreFailure, R.backoffShape, R.timeout, R.circuitBreaker, H.outboxDown | PASS |
| NFR-32 | R.restartReplays, R.crashDoesNotBurnAttempts, R.retryAfterStoreFailure | PASS |
| NFR-35 | T.regionsAreSeparate, C.indexMatchesStub | NOT TESTABLE for residency (per-region keying only) |
| NFR-38 | B.coveragePerRegion, T/H metric counts | PASS |
| NFR-40 | CFG (flag off creates no beans, additive wiring) | PASS partial; schema additivity NOT TESTABLE |
| NFR-41 | H.flagOff, B.flagOffFirm, S.hooksThenPublish | PASS |
| NFR-42 | H.reEnableResumes | PASS for hooks and backfill (D3 fixed: CaptureRegressionTest.backfillResumesAfterReEnable, BackfillJobTest.flagOffDeferredThenProcessed) |
| NFR-43 | none | NOT TESTABLE (rollout/design review) |

## Risky-test verification (does the test assert its DisplayName?)

- Seeding rules: all seven rules verified against assertions in T (createSeeds, updatingNeverTouchesBaseline, templateChangeByOpenClears, templateChangeByCreateOrBackfillReseeds, backfillSeedsAbsentBaseline, realAcceptBeatsSeed, noOpsSeedNothing). OK. Gap: no test of an accept with decidedAt == EPOCH (would be NOOP_CONFLICT against a seed); unrealistic.
- NOOP_NO_RECORD / NOOP_CONFLICT on upsertBaseline: T.acceptNoRecord, T.acceptOtherTemplate, T.acceptConditional (equal ts different version). OK, counters asserted.
- Hook never throws / flag-off: H.neverThrows, H.flagOff assert no exception, no outbox row, counter 4, other firm unaffected. Minor: the throwing-flag case asserts no outbox row but not the HOOK_FAILED count.
- Relay restart: R.restartReplays kills the relay with an Error after applying call 4 and before the ack, builds a new relay, asserts 10 records, APPLIED=10, NOOP_DUPLICATE=1, DLQ empty. Real, not tautological.
- Breaker: R.circuitBreaker asserts store call counts (3, then 3, then 4), BREAKER_OPENED and SKIPPED, half-open single probe, close, all 10 applied. Real. Note: the breaker is global, one poison message class could trip it for all (design note).
- Backfill re-check and load-start timestamp: re-check before load is real (B.hookCoveredBetweenEnumerationAndLoad). Load-start timestamp is WEAK (W2).
- Failed load never writes: B.failedThenRecovered, permanentlyFailing assert `find` is empty each time. OK.
- Concurrency never exceeded: loader-side maxInFlight with real virtual threads, gated by latches; runtime raise and lower asserted. OK.
- Coverage per region: B.coveragePerRegion asserts EU 1.0, CA 0.5 and the Micrometer gauge. OK.

## Weak evidence

- W1 (NFR-1, NFR-22): H.noLoadAnywhere is a reflection check on method names containing "load" plus an `AtomicInteger loads` that nothing increments; the final `assertThat(loads).hasValue(0)` is a tautology. S.hooksThenPublish has the same dead `loaderCalls`. The real proof is structural: CaptureHooks, CaptureRelay and PublishHandler take no EfLoader (compile-time). Only B.onlyBackfillLoads is behavioural (loader spy is the real InMemoryEfLoader), and it shows only that the hook path and PublishHandler were not given the spy.
- W2 (load-start timestamp): the fake clock does not advance during the load, so the test cannot tell start-time from end-time stamping. Implementation uses the start time (BackfillJob.load, `startedAt`), so no defect, but the test would not catch a regression.
- W3 (retry re-check): retries also pass the record re-check in code, no test covers a retried EF that a hook covered meanwhile.
- W4 (NFR-2): no assertion on latency; correct, as the target is "to agree".

## Defects D1-D3 (all FIXED 2026-10-30; probes converted to capture/CaptureRegressionTest.java; ./mvnw test: Tests run: 327, Failures: 0, Errors: 0, Skipped: 0)

- D1 (medium, FR-4, NFR-32): a transient failure of an EF's first VERSION capture, followed in the same batch by its accept, loses the accept permanently. The create is nacked and backs off, the accept is claimed in the same pass, store returns NOOP_NO_RECORD and the relay acks it (CaptureRelay.drainOnce, InMemoryTemplateVersionRecordStore.upsertBaseline). After the create is retried the baseline stays the EPOCH seed (version at creation), not the accepted version. ADR 0002 cl.3 accepts NO_RECORD for EFs never captured, but not this reorder caused by retry. Expected baseline v5, actual v1 seed. Suggested fix: treat NOOP_NO_RECORD from the relay as a nack with backoff for a bounded number of attempts (then DLQ), or keep ordering per EF.
- D2 (medium, FR-6, FR-7): an exception from `flags.enabled` or `store.find` inside BackfillJob.dispatch aborts the step after the EFs were polled from `pending`/`work`; they are neither retried nor re-queued and the enumeration token has moved on, so they stay record-less until a new job instance. Expected: every enumerated EF is eventually attempted. Probe: PROBE-2.
- D3 (low to medium, NFR-42, NFR-5): a flag-off EF is skipped and forgotten (documented in the class comment). After the firm flag is re-enabled the running job never returns to it; coverage for those EFs depends on a restart or new enumeration. Expected per plan TC-19: re-enable resumes. Probe: PROBE-3. Could be accepted as design if the restart is documented; then delete the probe.
- Observations, no probe: nothing in src/main calls CaptureHooks.retryFallback or CaptureRelay.drainOnce (no scheduler in FanoutConfiguration), so in the wired app the outbox is never drained and the fallback never replayed; the fallback queue is lost on crash.

## Scope checks

- Pre-existing test classes: every pre-existing test file has an mtime at or before 16:22 (spec 02 work); the earliest spec 01 file is 16:30. No pre-existing test file changed. New test files only: capture/*, config/FanoutConfigurationCaptureTest.java, and this matrix.
- Production files changed after spec 01 start: docs/ADR/0002 and 0003 (user-approved clarifications), new capture/* and adapter/memory/{InMemoryCapture*,InMemoryTemplateVersionRecordStore,InMemoryEf*,RecordBacked*}, new config/CaptureProperties.java, and a modified config/FanoutConfiguration.java (CaptureWiring, flag off by default). Nothing else outside expected paths. git has no commits, so this rests on mtimes, not diffs.

## Coverage

Primary: 12 of 12 requirements have a named passing test; 3 are mechanism-only (NFR-2 indicative, NFR-4, NFR-5). Cross-cutting: 13 rows, 9 with passing tests (NFR-22 weak, NFR-23 and 40 partial), 1 failing in part (NFR-42 backfill), 3 not testable (NFR-28, 35 residency, 43). Not testable: NFR-2 real p95, NFR-4 real limit (Q1), NFR-5 14-day coverage, NFR-35 residency, NFR-43 rollout, NFR-29 retention, NFR-28 reconciliation.

# Spec 04 (pending update status and decisions) - T6 QA review, 2026-10-30

Run: `./mvnw test` in fanout-worker. Real summary line after the Q1 and Q2 fixes: `Tests run: 385, Failures: 0, Errors: 0, Skipped: 0, BUILD SUCCESS` (378 before the QA probes were added and fixed; the two probes are now regression tests in `status/QaProbeDecisionIntegrityTest.java`).

## Primary requirements

| ID | Test (class: method) | Status |
|---|---|---|
| FR-22 | SD.enumExhaustive, PQ.listPagesAndTotals; badge UI | pass (Java); UI not built in this run |
| FR-23 | SD.nothingNewer, SD.newerEligible, SS.refreshWritesPending | pass |
| FR-24 | SD.noRecord | pass |
| FR-25 | SD.noBaseline, SD.thrown, SS.failureInjection (records, graph, decisions, store), PQ.storeFailure, DS.storeFailure | pass (with caveat W1) |
| FR-26 | SD.accumulated (3 versions to one update, Derived has 3 components, no count) | pass |
| FR-27 | DS.allFieldsRecorded, DS.duplicates | pass |
| FR-28 | SD.declinedLatest, DS.declineRefreshes, DS.declineThenAccept | pass |
| FR-29 | SD.declinedLatest (baseline equal), DS.declineKeepsBaseline (record equal before/after) | pass |
| FR-30 | SD.declinedThenLater, DS.declineKeepsBaseline (v3 pending from v1) | pass |
| FR-31 | DS.allFieldsRecorded, DS.acceptUsesEffectiveBaselineThenRelay, SD.acceptedNewer | pass |
| FR-32 | DS.declineRefreshes, DS.acceptUsesEffectiveBaselineThenRelay, DS.duplicates (no second refresh) | pass |
| NFR-13 | PQ.p13RowVisibleAfterPublish (in-memory, indicative only, prints microseconds) | pass as indicative; real p95 not testable here |
| NFR-14 | PQ.p14LargeFirm (40,000 rows; CountingStore asserts rowsTouched = 50 and one counts call), SS.storePaging | pass (mechanism); real p95 not testable here |

## Cross-cutting rows (plan 04 section 3)

| ID | Test | Status |
|---|---|---|
| NFR-21 | SM.noLoaderDependency, StatusStore has no load method | pass |
| NFR-23 | PQ.p14LargeFirm (40,000 max firm); 800,000 total not exercised | pass (partial) |
| NFR-24 | PQ.p24ReadsWhileWriting (no exception, counts equal rows after concurrent put replacing statuses) | pass in-memory; real isolation not testable here |
| NFR-28 | SM.springFree, SS.refreshFirmRepairs, DS.refreshFailureDoesNotUndo (reconcile repairs) | pass |
| NFR-30 | DS.hookFailureDoesNotUndo, DS.refreshFailureDoesNotUndo, SS.lookupFailure | pass (UI error state: not built) |
| NFR-31 | none | not testable here |
| NFR-33 | DS.foreignFirm, PQ.firmIsolation, QaProbeDecisionIntegrityTest.craftedEfRefCannotDecideForForeignEf, unknownEfIsRejected, PQ.detailChecksLookup | PASS (defect Q1 fixed: owner resolved through EfFirmLookup; IAM beyond unit level not testable) |
| NFR-34 | DS.appendOnly (reflection on port and in-memory class); 7-year retention | pass (no mutation method); retention not testable here |
| NFR-35 | no test; regional store by construction, no global type used | not testable here (design review) |
| NFR-38 | PQ.storeFailure (timer and error counter by op) | pass |
| NFR-40 | SC (flag default off, no beans; on = decline visible through query) | pass |
| NFR-41 | SS.flagOff, DS.flagOff, PQ.flagOff | pass |
| NFR-42 | not owned here; additive config only (SC) | pass (config level) |
| NFR-43 | rollout | not testable here |

Coverage: FR-22..FR-32 and NFR-13/14: 13 of 13 have a named passing Java test (FR-22 badge and NFR-13/14 real p95 excluded as UI or not testable). Cross-cutting: 10 of 14 listed rows have tests (NFR-33 and NFR-30 partial); NFR-31, 35, 43 not testable; NFR-34 retention not testable.

## Risky-test verification (does the test assert its claim?)

- Never UP_TO_DATE on failure: SS.failureInjection first makes the true answer UP_TO_DATE, so a wrong fallback would show; asserts UNKNOWN in row and store for failing records, graph and decisions; flaky store falls back to UNKNOWN. Real. SD.thrown covers derive exceptions (null graph, unknown baseline version, throwing graph). Weak spot W1 below.
- markUnknown fix: MU asserts pending v7 stays (status, target, source DERIVED, counts) after dead letter for v5, and UNKNOWN/FANOUT_UNKNOWN for v7. Real.
- Decorator agrees with the marker after reevaluate: SS.reevaluateAgrees compares the row to the marker state. Real.
- Q8 declined-latest is UP_TO_DATE and baseline unchanged: SD.declinedLatest. Real.
- FR-30 and FR-29: real, assert pending target and baseline v1 after a v3 appears.
- FR-26: real; one update, no count field.
- Effective baseline after accept: DS.acceptUsesEffectiveBaselineThenRelay asserts record baseline still v1, row UP_TO_DATE with baseline v2, then after relay. Real.
- Duplicates: DS.duplicates asserts hook and refresh not called again. Real.
- Foreign firm and flag-off: assert rejection reason and nothing recorded. Real; Q1 fixed (firm resolved by lookup, unknown EF rejected).
- Append-only: reflection with name regex; checks the port and in-memory class. Adequate but name-based.
- P-14: not a tautology; the CountingStore asserts 50 rows touched and one counts() call. O(1) of totals is by construction of the in-memory arrays. P-13 and P-14 only print timing and are labelled indicative; no timing assertion. Correct.
- Stale refresh clobber: SS.staleRefreshDoesNotClobber gates the first snapshot and checks the second refresh blocks (thread state), then final row is v3 pending. Real.

## Weak evidence and gaps

- W1: there is no test where the store is down while a healthy UP_TO_DATE row already exists and a fan-out dead letter (markUnknown) or refresh arrives. In that case the put fails, the exception is swallowed and counted (StatusRefreshingMarker.after), and the old UP_TO_DATE row stays. This is documented and counted and reconciliation repairs it, but it is a window where a failure shows as up to date. Design risk, no probe.
- W2: no test that a flag-off marker call followed by flag-on leaves a stale row until refreshFirm runs (documented behaviour; only refreshFirm no-op when off is tested).
- W3: paging token stability under inserts between pages is not tested (token is the last EF id, so stable by design).
- W4: no test that detail uses the owner lookup for a different region.

## Defects Q1 and Q2 (both FIXED 2026-10-30; probes are now regression tests in status/QaProbeDecisionIntegrityTest.java; unknown-version decisions rejected and skipped in derive)

- Q1 (high, NFR-33): DecisionService checks only `ef.firm() == caller.firm()`; the EfRef is built from client input. A caller of firm F who sends EfRef(efId owned by G, firm F) passes. The decision is stored keyed by EfId only (InMemoryDecisionStore), so G's EF derive then sees the decline and G's pending update disappears (cross-firm effect). Expected: rejected or no record against G's EF. Actual: decline v2 recorded for G's EF. Suggested fix: resolve the owner via EfFirmLookup (or the record store) and reject when it differs. Probe: craftedEfRefCannotDecideForForeignEf.
- Q2 (medium, FR-25, FR-23): accept (and no validation) of a version unknown to the graph is appended; StatusDeriver then throws in graph.isNewer for every later derive, so the EF is UNKNOWN permanently and append-only gives no undo. Expected: rejected or ignored; EF stays UPDATE_PENDING for v2. Actual: UNKNOWN. Suggested fix: validate the version against the graph before append, and/or skip accepted versions the graph does not know in derive. Probe: bogusAcceptDoesNotPoisonStatus.

## Scope checks

- Pre-existing test classes (capture/*, config/FanoutConfigurationTest, graph, fanout, system, etc.): all mtimes at or before 16:48, spec 04 files start 16:54. 52 test files present, none removed. New test files: status/* (6), config/StatusConfigurationTest, the probe. Only TEST-MATRIX.md was appended.
- src/main files changed since spec 04 start: status/*, adapter/memory/{InMemoryDecisionReader, InMemoryDecisionStore, InMemoryEfFirmLookup, InMemoryStatusStore}, config/StatusConfiguration. Docs touched: ADR 0008, 0009, 0014, docs/02-architecture.md, README, specs 00/04 and plans; expected. git has no commits, so this rests on mtimes.

## Not testable here

NFR-13 real p95, NFR-14 real p95, NFR-24 real isolation from fan-out, NFR-31 availability, NFR-33 IAM beyond unit level, NFR-34 7-year retention, NFR-35 residency, NFR-43 rollout, all UI rows (FR-22 badge, filter, live region, UI-25, UI-30, UI-32, UI-41): UI not built in this run.

# Spec 05 Summary generation (Java side; Angular UI not built in this run)

Run: `./mvnw test` before the QA files: `Tests run: 470, Failures: 0, Errors: 0, Skipped: 0, BUILD SUCCESS` (385 before spec 05, so 85 new). QA added `summary/QaProbeSummaryResilienceTest` (4, red), `summary/QaProbeSummaryValidatorTest` (6 cases, red) and `summary/QaGapSummaryResidencyTest` (1, green); with them the suite is 481 run, 10 failures, all in the two probe classes. Abbreviations: SS = SummaryServiceTest, SV = SummaryValidatorTest, ST = SummaryStoreTest, SR = SummaryRecordTest, SQ = SummaryQueryTest, SP = SummaryPortsTest, EH = EvalHarnessTest, SC = SummaryConfigurationTest, SM = SummaryModuleStructureTest.

| ID | Test | Status |
|---|---|---|
| FR-33 | SQ.generatingThenReady, SQ.notPending (pair = row baseline/pendingTarget) | pass (UI U1 not built) |
| FR-34 | SS.s1, SS.countsOnce, SQ.generatingThenReady | pass |
| FR-35 | SS.s11 (only eligible-target baselines, skip unknown, idempotent), SS.s11Replay, SC (publish flow) | pass |
| FR-36 | SS.s1, SS.s2 (32 threads), SS.s3, SQ.sharedAcrossFirms, ST.moveCurrentIfAbsent | pass |
| FR-37 | SS.modelRequest, SP.requestShape, SP.fakeCaptures | pass |
| FR-38 | SV.rawPaths (8 variants), SV.exactPath, SV.prose | PASS (QA-5, QA-6 fixed; see SummaryValidatorRegressionTest; residual false positives/negatives listed in README) |
| FR-39 | SV.valid, SR (Statement needs id) | pass (UI marker U3 not built) |
| FR-40 | SR record round trip, SR hash test, SS.s1 (model, prompt) | pass |
| FR-41 | SV.empty, noIds, unknownId, oneBadRejectsAll; SS.s4Rejected (nothing stored) | pass |
| FR-42 | SS.s4Model/s4Rejected/s4Diff/s4Store, SQ.failureUnavailable, SQ.tamper, SQ.storeDown | pass |
| FR-43 | SQ.generatingThenReady (label), SR.aiGenerated | pass |
| NFR-15 | ST.noUpdateOrDelete, ST.history unmodifiable, SR lists unmodifiable, SS.s14, SQ.tamper | pass |
| NFR-16 | SR round trip, ST.supersede (old readable) | pass; 7-year retention not testable |
| NFR-17 | SR alias test, EH.aliasRejected, SC (alias fails startup) | pass |
| NFR-18 | EH 0.02 fails, 0.01 passes, outsideSupportedSet, rawPath; EH.goodModelPasses | pass |
| NFR-19 | EH 5 of 100 passes, 6 fails, omittingHalf; EH.modelExceptionReported | pass |
| NFR-20 | SS.p1 (injected clock latency); real latency not testable | pass (mechanism) |
| NFR-23 | SS.s2 / SS.s11 (work per distinct pair) | pass |
| NFR-26 | X unset | not testable |
| NFR-27 | SS.s3, SS.s11Replay, SP enqueue idempotent, ST.append idempotent | PASS (QA-1, QA-2 fixed: deterministic generation ids, SummaryResilienceRegressionTest); earlier finding: duplicate records on partial failure and lease takeover |
| NFR-29 | SS.s4Model, SS.backoffCap, SP deadLetter, SQ.failureUnavailable | pass; no 14-day retention or redrive tested (none exists) |
| NFR-30 | SQ.failureUnavailable, SQ.storeDown, SQ.tamper | pass |
| NFR-31 | production data | not testable |
| NFR-32 | SS.s4Model (retry), SP queue tests | partial; QA-3 and QA-4 fixed (lease-busy defer, Error handling); in-memory queue still loses jobs on restart by nature |
| NFR-33 (xref) | SQ.foreign | pass |
| NFR-35 | SM.noLoader, SP.requestShape, QaGapSummaryResidencyTest | pass |
| NFR-36 | SR.PairKey no firm/EF, QaGapSummaryResidencyTest (BaselinesInUse returns Set<VersionId>, no EfId/FirmId in any summary type except SummaryQuery) | pass |
| NFR-37 | SS.modelRequest, SP.requestShape, EH.pinnedRecorded | pass |
| NFR-38 | SS.s4* (failedCount per kind), SS.p1 (latency) | pass |
| NFR-40 | infra | not testable |
| NFR-41 | SQ.s5 (no job, no model call, store unchanged), SC default-off | pass |
| NFR-42 | SQ.s5 (stored summaries untouched) | pass |
| NFR-43 | deploy practice; SC shows beans absent when off | not testable (partial) |

Coverage: FR-33..FR-43 11 of 11 and NFR-15..NFR-20 6 of 6 have a named passing Java test (FR-33/39/43 UI parts, NFR-16 retention and NFR-20 real latency excluded). Cross-cutting rows: 11 of 16 listed have a test (NFR-23, 27, 29, 30, 33, 35, 36, 37, 38, 41, 42); NFR-26, 31, 40 not testable, NFR-32 and NFR-43 partial.

## Risky-test verification

- One model call under 32 threads: SS.s2 asserts model.calls()==1, history 1, generatedCount 1. Real, but the fake model is instantaneous, so overlap relies on lease plus the "current exists" re-check; the 32-way lease race itself is proved in SP. Adequate, not the strongest (no latch-held slow model).
- Replayed job: SS.s3 enqueues RETRY after success and asserts calls 1, history 1, depth 0. Real.
- Failure, rejection, absent diff, DiffSource throws, store down: SS asserts history empty, find empty, metric per FailureKind, retry or DLQ. Real. Pending status: SQ.failureUnavailable asserts status still UPDATE_PENDING after DLQ. Real.
- Validator: empty, no ids, unknown id, path variants and false-positive prose ("Version 1.2", "and/or", "e.g.") assert Kind. Real. Holes found by probes below.
- Immutability: ST reflection (no update/delete name), history copy unmodifiable, SR lists unmodifiable, tamper through current() (ST) and through SummaryQuery (SQ.tamper). Real; the name-based reflection check is shallow.
- Alias rejection: SR/EH/SC. Real, but ModelId alias list is a small deny-list ("latest", "default", "stable", "current", "newest", suffixes); an alias such as "gpt-4o" or "model-v2" passes by design.
- ModelRequest exactly model/prompt/diff: SP.requestShape asserts record component names exactly. Real.
- Residency: SM only checked loader types; QaGapSummaryResidencyTest added to scan all summary types for EfId/FirmId. Green.
- Supersede keeps history and old record readable, shared across firms: SS.s14, SQ.sharedAcrossFirms. Real.
- onPublished only for baselines whose eligible target is the new version: SS.s11 (v1 and v2 enqueue for v3; v3 itself and ghost not; older event enqueues nothing). Real.
- Flag off, foreign firm, label: SQ.s5, SQ.foreign, SQ.generatingThenReady. Real. Non-pending rows: only UP_TO_DATE and UNKNOWN exist as statuses; both covered.
- Eval harness: boundary 1 of 100 passes, 2 fails, 5 of 100 passes, 6 fails; bad model gives non-zero exit; ModelException reported. Real. Gap: harness catches ModelException only, a RuntimeException from a client aborts the run (still non-zero, but no report).

## Defects QA-1..QA-6 (all FIXED 2026-11-01; probes are now SummaryResilienceRegressionTest and SummaryValidatorRegressionTest; ./mvnw test: Tests run: 494, Failures: 0, Errors: 0, Skipped: 0)

- QA-1 (medium, NFR-27, FR-36): `SummaryService.processNext` does append then moveCurrentIfAbsent as two steps. If the pointer move throws, the job is retried with a new generationId, so the first record stays as an orphan. History for one pair then holds 2 records, one never current. Probe: QaProbeSummaryResilienceTest.orphanRecordOnPointerFailure. Expected 1 record, actual 2. Fix: make the retry reuse the appended record (deterministic generationId per pair and attempt chain, or an atomic append-and-set-if-absent on the store).
- QA-2 (medium, NFR-27): lease takeover (TTL 2 min elapses while a slow worker still runs, queue redelivers) gives two model calls and a late append by the slow worker after the pointer already exists; moveCurrentIfAbsent returns false but the record stays in history. Probe: leaseTakeoverLeavesOrphan (history 2, expected 1). Same root cause as QA-1; the second model call is inherent without fencing, the orphan is avoidable.
- QA-3 (medium, FR-35, NFR-20, NFR-32): `processNext` acks and drops a job when the lease is held elsewhere ("its outcome is what counts"). If the holder crashed, or the holder is a supersede that failed, nothing retries: a precomputed summary is never produced unless a user opens the EF. Probe: jobLostWhenLeaseHeldByDeadWorker. Fix: retryLater when the lease is busy instead of ack.
- QA-4 (low-medium, NFR-30, NFR-32): only RuntimeException is caught. An Error from the model client (for example NoSuchMethodError from an SDK mismatch) leaves the pair in `outstanding` forever, so `enqueue` is a no-op and `ensure` shows "generating" for ever. Probe: errorStrandsJob.
- QA-5 (low, FR-38, FR-42): the dotted-path heuristic false-rejects legitimate prose: "U.S.A.", "Ph.D", "ends.The" (missing space) are rejected as RAW_PATH, so a good summary burns retries and may reach the DLQ. Probe: QaProbeSummaryValidatorTest.legitimateProseNotRejected (3 cases).
- QA-6 (low, FR-38): paths with numeric segments are accepted unless they equal a stored diff path exactly: "clauses.0.title", "fields.1", "fields.2.label" pass. Probe: numericDottedPathRejected (3 cases).
- Observations (no probe): the DLQ has no redrive API, so `ensure` on a dead-lettered pair returns Failed for good (ADR says operator redrive; none exists in the port) and a new publish replay is the only recovery; dead-letter list has no 14-day retention (NFR-29 "to agree"). `ensure` also reports Failed while a newer job for the same pair is outstanding. In-memory queue and leases do not survive restart (stated limit of the in-memory adapters). Shared summaries cannot leak EF content: the diff and statements derive from template diffs only; residency scan green.

## Scope checks

- No pre-existing test or main file changed: files newer than 2026-11-01 are only the spec 05 additions (summary package and tests, summary adapters, SummaryConfiguration, SummaryConfigurationTest, cases.txt), fanout-worker/README.md and ADR 0010-0012. Pre-existing test classes all still exist (52 earlier test files plus status/*). git has no commits, so this rests on mtimes.

## Not testable here

Object Lock retention (NFR-16, NFR-15 at storage level), NFR-26 cost X unset, NFR-31 availability, NFR-40 and NFR-43 infra and deploy practice, bit-identical model reruns (ADR 0012), real model latency (NFR-20), UI rows U1..U6 (Angular not built), audit-details visibility.

## UI and web layer (T7, 2026-11-01)

Run: `./mvnw test` in fanout-worker: 518 tests, 0 failures (24 web tests: PendingUpdateApiTest 16, SummaryApiTest 4, WebConfigurationTest 4; 494 baseline unchanged). `npx ng test --watch=false` in pending-update-ui: 26 passed (4 files); `npx ng build`: success, 259 kB initial. Adding the probes below makes the suites red on purpose (Java 518 + 3 probe failures; UI 26 passed + 1 failed). Also ran the demo profile (`spring-boot:run`, port 18089) and curled it: foreign EF and unknown EF both `404 {"error":"NOT_FOUND"}`; `?firm=firm-other` and body `firm` ignored; ef-1002 READY with label "AI-generated" and no path in the JSON; ef-1003 GENERATING; ef-1006 summary `200 UNAVAILABLE` while its row stays UPDATE_PENDING; no headers `401`; decline then GET shows UP_TO_DATE.

| Req / case | Test | Evidence read | Status |
|---|---|---|---|
| UI-22 badge text and icon per status | status-badge.component.spec (3) ; list spec | `toStatusView` exhaustive, default branch is `unknown` | pass |
| UI-25 / FR-25 failure never up to date | list "503 shows error"; list "UNKNOWN row"; detail "503 on the row"; PendingUpdateApiTest.statusUnavailable | `toApiError`: every status except 401/404/422 and non-HTTP errors maps to `unavailable`; pending row missing a version maps to unknown | pass |
| UI-30 / NFR-30 view error leaves page usable | list Retry; detail decision 503 keeps row | decide error path restores `loaded` with the old row | pass |
| UI-32 / FR-32 refreshing state | detail "after accept/decline shows refreshing then the refreshed row; body only {version}" | `decide` guards on `loaded`, so a double click is ignored (state set synchronously to `refreshing`) | pass |
| UI-41 / NFR-41 flag off renders nothing | list and detail "404 FEATURE_OFF"; WebConfigurationTest.offByDefault; PendingUpdateApiTest.listFeatureOff, decisionFeatureOff | see finding QA-W5 (detail flag off is NOT_FOUND by design, UI shows "not found") | pass with note |
| U1 READY | detail spec SummaryPanel U1 | | pass |
| U2 GENERATING | U2 | no timeout, "Check again" button | pass |
| U3 marker opens diff item | U3 | | pass |
| U4 UNAVAILABLE, status stays pending | detail U4; SummaryApiTest.failedIsUnavailableAndStatusUnchanged, exceptionIsUnavailable | controller catches RuntimeException on both calls | pass |
| U5 AI label, audit block | U5, a11y FR-43 | label comes only from the server string, see QA-U1 | pass, one probe fails |
| U6 summary 404 hides panel; escaping | U6, "escaping <script>" | grep for innerHTML, bypassSecurity, DomSanitizer, outerHTML over pending-update-ui/src and fanout-worker main: none (one comment) | pass |
| NFR-33 firm never from client | PendingUpdateApiTest.firmInQueryOrBodyIgnored, foreignEqualsUnknown, unauthenticated; list spec checks headers; decide spec checks body | only `CallerResolver` reads headers; `DecisionApi` builds EfRef from `caller.firm()`; DecisionService re-checks owner | pass |
| Dev headers marked | CallerResolver javadoc, WebConfiguration javadoc, fanout-worker README, application-demo.properties, UI interceptor, environment.ts, UI README | | pass |
| Summary path not exposed | SummaryApiTest.generatingThenReady (path absent) ; live curl | `SummaryBody.DiffItemDto` has id, section, change only | pass |
| Web beans absent by default; pom | WebConfigurationTest (4) | pom adds only starter-web, spring-boot-maven-plugin, starter-test (test scope) | pass |
| DemoApplication isolation | none automated | only `@SpringBootApplication` in the tree, scans `demo` only, `DemoSeed` is `@Profile("demo")`; no test or main class scans `com.caseware.fanout` | pass by reading and a live run; no test |
| No existing Java test changed | mtimes | every test file outside `web/` is dated 2026-10-30 15:47 to 2026-11-01 08:52, before the first web file (09:09); 63 classes ran green. No git history, so mtimes only | pass |

### Findings (probes)

- QA-W1 (low, NFR-33 ordering): a malformed JSON body on accept/decline with no identity headers returns 400, not 401, because Jackson fails before the controller calls `CallerResolver`. Reproduced live and in `web/QaProbeWebTest.unauthenticatedBeforeBadBody`. Fix: resolve the caller in a filter or interceptor before argument binding, or take the body as a String.
- QA-W3 (medium, FR-27/FR-31/FR-25): `DecisionApi` checks only that the version exists in the graph, not that it is the row's `pendingTarget`. A stale or older known version is recorded as RECORDED (accept v2 while v3 is pending). Baseline did not regress in the probe, but the audit record holds a decision the user never saw, and the UI's "That version is no longer valid" message can never fire for a stale click. Probe: `QaProbeWebTest.staleVersionRejected`. Fix: reject (422) when the version is not the current pending target (or is not newer than the baseline).
- QA-W4 (low-medium): a decision on an UP_TO_DATE EF is recorded (accept v1 on ef3 at v3 returns 200 RECORDED). Probe: `decisionOnUpToDateRejected`. Same fix as QA-W3. Also live: accept on the UNKNOWN row ef-1005 turned it UP_TO_DATE; the UI never offers this, the API allows it. Decide whether an UNKNOWN row may be resolved by a decision.
- QA-U1 (low, FR-43): the "AI-generated" label is rendered only from the server `label` string. If the server sends an empty or missing label the READY summary shows with no AI marker. Probe: `src/app/qa-probe.spec.ts`. Fix: hardcode the marker in the template and show the server label as extra text. The existing U5 test cannot catch this because the fixture supplies the label.
- QA-W5 (low, NFR-41 consistency): `GET /{efId}` with the flag off returns 404 `NOT_FOUND` (documented, to keep NFR-33 indistinguishability), but `UpdateDetailComponent` then renders the heading and "This Engagement File was not found" instead of nothing; the list renders nothing. No probe (documented behaviour); a consistent decision is needed.
- Observations, no probe: DECISION 503/StatusApi 5xx other than 503 all show unknown or "could not be recorded", never up to date (correct). Previous-page stack traced by hand (push current on Next, pop on Previous, explicit null token is kept), no bug found; no test covers Previous. Filter counts are server totals for all EFs and the filter narrows only the loaded page; the UI says so in a note, but a count of 5 beside an empty page of "Update pending" is possible and confusing. 401 shows "not signed in" with a Retry button that cannot help in a dev setup. XSS via efId: route param is interpolated and `encodeURIComponent` is used for URLs; fine. CORS: none configured, dev relies on `proxy.conf.json` (documented in README as not done). Unhandled non-`StatusUnavailableException` runtime errors on list/detail give Spring's default 500 body, which the UI treats as unavailable (safe).

### Not covered

No real browser run (jsdom/Vitest only, canvas getContext not implemented warning), no 360 px layout check, no colour contrast check (axe in jsdom does not compute contrast), no keyboard-only walkthrough beyond axe rules, no test for the Previous pager, double click on Accept, race between refresh and summary panel (analysed by reading: panel is destroyed while `refreshing`), CORS or the proxy, or the demo startup. Not testable: auth (stub only), residency of the web tier, performance of the list at 800k EFs.

### Left-out list (spec 04 and 05 mockups vs build)

Per pending-update-ui/README.md "Left out": mockup toolbar simulations (publish, withdraw, retry, state switcher), decision log/history, server-side filtering, count of versions in the cumulative range, withdrawn-version handling, real authentication. Per fanout-worker/README.md "Not done": authentication, CORS, scheduler (GENERATING stays), persistence, rate limiting, OpenAPI file. All are stated; none is traced to a test, and none contradicts a requirement checked above (withdrawn-version handling lives server-side in the graph tests). Items I had not verified in either README (search, bulk accept, flag admin UI) are not claimed.
