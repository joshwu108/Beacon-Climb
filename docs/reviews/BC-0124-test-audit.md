<!-- Saved by the top-level session from the read-only test-auditor subagent output, 2026-10-15. Audited at 94207fe. -->

# BC-0124: Test audit of the vertical slice (read-only)

Auditor: claude-test-auditor (Opus), 2026-10-15. Audited at `94207fe`. HEAD later moved to `4426b5b`, which only touches `docs/runbooks/dev-stack.md`, so nothing here changes. The working tree is clean and no repo file was changed. All mutations and probes ran in copies under `/private/tmp/claude-501/-Users-joshuawu-Beacon-Climb/d626ff7b-83d6-4e55-b9ba-0f7d21a68317/scratchpad/` (`dry/<mutation>/`, `probe/`, `hist/<sha>/`, logs in `results/`).

**Summary:** the attempt idempotency and revision tests, the ranking tie-break and the feature-recompute tests catch every mutation I tried. Three things are not proven:
- **E2E double submit:** the double-click only ever sends one request to the server.
- **Feature snapshot ordering:** recomputing at an older cutoff `W` silently overwrites the live snapshot.
- **Recommendation write order and failure paths:** three mutations survived and no test exercises these paths.

## Baseline (commands actually run)

| Command | Result |
|---|---|
| `JAVA_HOME=…openjdk@21… ./gradlew :api:test --rerun` (real repo) | BUILD SUCCESSFUL in 45 s. 63 classes, **331 tests, 0 failed, 1 skipped** (`CatalogBenchIT`, opt-in) |
| Same command in the scratch control copy (`M00_control`) | 331 tests, 0 failed |
| `pnpm exec vitest run` in `/Users/joshuawu/Beacon-Climb/apps/web` | 24 files, **285 passed** |
| jqwik reports in `/Users/joshuawu/Beacon-Climb/services/api/build/test-results/test/*.xml` | `RankerPropertyTest` 5 properties, each `tries = 1000, checks = 1000`. `AttemptInvariantsPropertyTest` 25 and 200 tries. `FeatureRecomputePropertyTest` 100 tries. `RecommendationPropertyTest` 200 tries |
| Jacoco | Not configured in `/Users/joshuawu/Beacon-Climb/services/api/build.gradle.kts`, so I did not run `jacocoTestReport`. The coverage section below uses a class-inventory estimate instead |

## Findings, ranked by severity

### HIGH-1: The E2E "double submit" never reaches the server twice
- **What the test does:** `/Users/joshuawu/Beacon-Climb/test/e2e/slice.spec.ts` step d1 calls `save.dblclick()`. It then checks one row in the UI and via the API (`attemptsFor(...)` length 1, `all.body.items` length 1).
- **What the trace shows:** I extracted the stored traces (`test/e2e/test-results/slice-…-chromium-{desktop,mobile}/trace.zip`, run at 09:58Z) and listed every `POST /v1/me/attempts`.
  - Desktop sends exactly one POST for d1: key `ba61372e…`, 201.
  - Mobile also sends one: key `34e49f27…`, 201.
  - The only same-key pairs are the API-driven step g (`f27067bf…` desktop, `599affcd…` mobile: 201, then 200, then 409), and those are sequential.
- **Why it matters:** the "one canonical attempt after double submit" assertion is satisfied by the UI disabling the button. It does not show that the server handles a duplicate browser submission.
- **Missing test:** see the list at the end.

### HIGH-2: A recompute at an older `W` replaces the serving snapshot and makes a warm user cold again
- **Code:** `/Users/joshuawu/Beacon-Climb/services/api/src/main/java/com/beaconclimb/api/features/FeatureSnapshotRepository.java` upserts `ON CONFLICT (user_id, feature_schema_version) DO UPDATE` with no guard on `cutoff_w`.
- **Probe:** `probe/…/recommendations/AuditProbeStaleSnapshotIT.java` (scratch only). Recommend for a warm user, then call `features.recompute(user, T-1d)`, then recommend again.
- **Observed:** `PROBE warm cold_start=false after-old-W cold_start=true`, and the test fails.
- **Why it matters:**
  - `make recompute-features W=<past>` and the BC-0306 parity harness (ADR-0004 compares at equal `W`) will overwrite the live feature row.
  - Two concurrent attempt-write hooks are last-writer-wins with no monotonic check.
- **Missing tests:** none cover either case. The fix needs a design decision (monotonic guard on `cutoff_w`, or a separate table for oracle runs).

### HIGH-3: Recommendation persistence order and failure paths are untested
- **Survived mutations:** M11 (snapshot written after the ledger transaction commits), M12 (snapshot-store digest check removed) and M13 (snapshot schema guard removed). See the table.
- **What is never exercised:**
  - The 503 path when the snapshot quota is hit, at service level.
  - "Transaction fails, so the snapshot file this request created is deleted" (`RecommendationService.persist`).
  - "Store returns a different digest, so nothing is written."
- **Store-level tests:** `LocalFsSnapshotStoreTest` covers quota and canonical-byte checks only on the store itself.
- **Why it matters:** ADR-0003 requires the snapshot file to exist before the decision row is committed. Today a reordering bug would ship green.

### MEDIUM-4: The ranker serves duplicate candidate ids, and the generator cannot find it
- **Probe:** `AuditProbeRankerTest` feeds `[a, a, b, c]`.
- **Observed:** `Found duplicate(s): ["a:40"] in ["c:40","a:40","a:40","b:40"]`.
- **Generator:** `RankerPropertyTest.candidates()` uses `.uniqueElements(Candidate::routeVariantId)`, so duplicates are never generated. This goes against the audit brief ("generator covers duplicates").
- **Live exposure:** none today, because `CandidateRepository` SQL yields unique variants. It is still a ranker totality gap.
- **Generator coverage check:**
  - Shuffled order: yes (`Collections.shuffle`, `@ForAll long seed`).
  - Ties: yes (M06 was caught by the property).
  - Ungraded candidates: yes (`injectNull(0.1)`).
  - Cooldown with and without PROJECT: yes.
  - Widening: yes.
  - Duplicates: **no**.

### MEDIUM-5: The feature oracle (`FeatureComputer`) does not deduplicate, and the "duplicates" property does not test it
- **Probe:** `AuditProbeFeatureTest`.
  - `[r, r]` gives `attempts_7d:2` where `[r]` gives 1.
  - `[rev2, rev1]` of the same `attempt_id` gives `attempts_7d:2`, and the rev1 send is counted.
- **What the property actually checks:** `FeatureRecomputePropertyTest`'s "duplicates" are idempotent replays (kind 1). Postgres collapses them before `FeatureComputer` sees anything. Its "out-of-order revisions" are stale `If-Match` rejections (kind 3), not a lower revision arriving after a higher one.
- **Oracle independence:** the oracle (`ReferenceFeatures`) reads the same `attempts` table as the service. It never builds an independent de-duplicated model from the generated operations.
- **Verdict:** the backlog acceptance ("random event sets with duplicates produce the same snapshot as the de-duplicated set") is proven only for service plus database. It is not proven for the function ADR-0004 names as the parity oracle.

### MEDIUM-6: `ON CONFLICT DO NOTHING` is guarded by one timing-dependent test only
- **M01 result:** the only failure was `AttemptConcurrencyIT.twentyIdenticalConcurrentPostsCreateOneAttemptAndOneEvent` (`expected: 19L but was: 11L`, 8 requests got 500 from a duplicate key).
- **Why sequential tests miss it:** the sequential replay tests pass because `findByKey` pre-checks hide the missing clause.
- **Risk:** on a slower runner where the threads do not overlap, M01 would survive.
- **Missing test:** a deterministic test at repository level.

### MEDIUM-7: Audit-row immutability (V7 REVOKE) is untested
- **Claim:** ADR-0002 test matrix row "Audit and ownership: Immutable audit row".
- **Implementation:** `/Users/joshuawu/Beacon-Climb/services/api/src/main/resources/db/migration/V7__attempt_revisions_append_only.sql`.
- **Gap:** no test asserts that `beacon_app` gets "permission denied" on `UPDATE` or `DELETE attempt_revisions`. `ImporterTestSupport.deleteBoard` uses `schemaOwner()`, which only implies it. The equivalent ledger test exists (`RecommendationLedgerIT.appRoleCannotUpdateOrDeleteTheLedger`).

### MEDIUM-8: The recommendation property only ever runs cold-start users
- **Evidence:** all 200 recomputes logged in `TEST-…RecommendationPropertyTest.xml` have `feature_as_of=null`, and the property asserts `coldStart` is true.
- **Consequence:** cooldown, novelty, widening and GOAL_CHALLENGE_MATCH through the real service each rest on a single example IT.

### MEDIUM-9: Score arithmetic order rests on one example; no replay from persisted artifacts
- **Golden fixture:** `GoldenFixtureTest` cannot detect a reordered or regrouped score sum. The fixture has only two non-zero weights, and a two-term sum is commutative. M07 and M07b were caught only by `RankerTest.scoreIsTheLeftToRightSumOfTheFiveWeightedTerms` (`4603399395113026191L` vs `…192L`).
- **Off-line check:** of 15,120 input combinations over the generator domain with heuristic-v1 weights, 2,796 change bits under reordering. So the property cannot catch it either, because it compares against `Scoring.score` itself, which is a tautology.
- **Replay:** no test takes a live decision (row plus snapshot file), recomputes scores and order from the snapshot and `policy_config`, and compares the bits. ADR-0003 replay itself is BC-0404, but whether the snapshot carries enough to reproduce a decision is unverified today.

### LOW-10: Handoff totals are real but tied to the wrong commits; two commits are not green on their own
I ran each commit via `git archive` in scratch:

| Commit | Ticket | Result |
|---|---|---|
| `e958e22` | BC-0109 | **232 passed** (matches) |
| `6ce019d` | BC-0113 | 255 tests, **3 failed** (MigrationIT, which only expects V1–V3 there) |
| `babe75c` | BC-0110 | **279 passed** (the "279" claimed in the BC-0113 handoff) |
| `a041aa8` | BC-0111 | **does not compile** (`recommendations.decisions.Explanations` missing) |
| `d8c8822` | BC-0112 | **312 passed** (the "312" claimed in the BC-0111 handoff) |

This is a bisect hazard: `6ce019d` and `a041aa8` are not individually buildable.

### LOW-11: Claims I could not reproduce (no artifact or stack run)
- BC-0121: "subagent reported 10/10 consecutive green runs".
- BC-0109: "live smoke 201/200/409".
- BC-0113: "hook and CLI checksums equal live".

The BC-0121 "4 passed" result is consistent with `test/e2e/test-results/.last-run.json` (`"status":"passed"`) and the four trace/video directories. I did not run the compose stack.

### LOW-12: Weak test name
`RecommendationsEndpointIT.differentSessionIdMayChangeTieOrderOnly` asserts equal score bits and equal size only. It never checks that any change in order is a sha256 tie-break.

## Mutation results

All runs are the full `:api:test --rerun --continue` (331 tests) against a copy with exactly one mutation. Diffs are in `scratchpad/results/<M>.diff`.

| # | Mutation | Failing tests (or SURVIVED) |
|---|---|---|
| M01 | Remove `ON CONFLICT … DO NOTHING` (`AttemptRepository.insertIfAbsent`) | `AttemptConcurrencyIT.twentyIdentical…` only (19 expected, 11 got). Timing-dependent (MEDIUM-6) |
| M01b | Plain SELECT-then-INSERT: M01 plus drop the `UNIQUE(user_id, key)` in V3 | `AttemptConcurrencyIT.twentyIdentical…` (**5 × 201**, 15 × 500), `MigrationIT.goldenSchemaDoc…`, `MigrationIT.attemptsIndexes…`. The race is genuine |
| M02 | Fingerprint = sha256(raw body) instead of the JCS form | `AttemptCreateIT.semanticallyEqualBodiesShareOneFingerprintAndReplay` (200 expected, 409 got). BC-0109-JCS works |
| M03 | Drop `revision = :revision` from the PATCH UPDATE | `AttemptPatchIT.correctionThenRetraction…`, `AttemptPatchIT.ifMatchIsRequired…` (409 expected, 500 got), `AttemptConcurrencyIT.concurrentPatches…`, `AttemptInvariantsPropertyTest.revisionCounts…`, `FeatureRecomputePropertyTest` (DuplicateKey). Caught, but the backstop is the `attempt_revisions` primary key turning the request into a 500 |
| M04 | Skip the outbox insert | 8 tests: `AttemptConcurrencyIT` ×2, `AttemptCreateIT` ×3, `AttemptInvariantsPropertyTest`, `AttemptLogScrubbingIT`, `AttemptPatchIT.correctionThenRetraction…` |
| M05 | Replay ignores the fingerprint mismatch | `AttemptCreateIT.sameKeyWithDifferentBodyIsIdempotencyConflict` |
| M06 | Tie-break descending | `GoldenFixtureTest.reproduces…`, `RankerPropertyTest.scoresAreNonIncreasingAndTiesFollowTheSha256Key` |
| M07 | Score sum reversed | `RankerTest.scoreIsTheLeftToRightSum…` only. Golden and properties **do not** catch it |
| M07b | Score sum regrouped | `RankerTest.scoreIsTheLeftToRightSum…` only |
| M08 | Include RETRACTED rows in the recompute SQL | `FeatureRecomputeIT.retractedAttemptIsExcluded…`, `FeatureRecomputeIT.attemptWritesRecompute…`, `FeatureRecomputePropertyTest` |
| M09 | 7-day lower edge inclusive (`ofDays(7).plusMillis(1)`) | `FeatureComputerTest.windowsAreLowerExclusive…` (2 expected, 3 got), `FeatureRecomputePropertyTest` |
| M09b | 7-day window one hour short | `FeatureComputerTest.windowsAreLowerExclusive…`, `FeatureRecomputePropertyTest` |
| M10 | Checksum over unsorted pairs | `FeatureComputerTest.checksumIsSha256OfSortedIdRevisionPairs`, `FeatureRecomputeIT.recomputeTwice…`, `FeatureRecomputePropertyTest` |
| M11 | Snapshot file written after the ledger transaction | **SURVIVED** |
| M12 | Skip the snapshot-store digest verification | **SURVIVED** |
| M13 | Skip `schema.requireValidSnapshot` | **SURVIVED**. The file is still validated by `RecommendationLedgerIT` after the fact, but the pre-commit guard is unexercised |
| M14 | Ledger digest over non-canonical bytes | 15 tests, mostly 500s (the runtime digest check fires). `candidate_set_digest == sha256(file)` is asserted directly in `RecommendationLedgerIT.decisionRowDigestMatches…` |
| M15 | Feature hook does nothing | `FeatureRecomputeIT.attemptWritesRecompute…`, `RecommendationsEndpointIT.coldStartIsTrueForANewUser…` |

## Per-area verdict

1. **Idempotency (`attempts/*`): proven, with one fragility.**
   - Proven:
     - `AttemptCreateIT` covers IDS, JCS (`2.0` vs `2` plus reordered members replays as 200 with an identical body), TIME (exactly 5 m accepted, 5 m 01 s gives 422), and IDEMPOTENCY (409 with one row and one outbox row).
     - `AttemptPatchIT` covers REVISION, RETRACT and OWNERSHIP, with audit and outbox rows per revision validated against attempts.v1.
     - `AttemptInvariantsPropertyTest` checks that revision == audit row count == outbox row count over random operation sequences.
   - Fragility: `ON CONFLICT` is covered only by the race test (MEDIUM-6), and audit immutability is untested (MEDIUM-7).
2. **Determinism and ranking: tie-break proven; arithmetic order proven by one witness only.**
   - The tie-break is caught twice.
   - Arithmetic order is caught only by a single RankerTest example (MEDIUM-9).
   - The generator lacks duplicates (MEDIUM-4).
3. **Feature recompute: all three requested mutations caught**, each by at least two tests. The jqwik generator does produce idempotent replays and stale revisions, but not duplicate or lower-revision rows at the oracle (MEDIUM-5). Window edges sit exactly in `OFFSET_HOURS` (167/168/169, 719–721, 2159–2161).
4. **Recommendations and ledger:**
   - Proven:
     - `candidate_set_digest == sha256(file bytes)` (`RecommendationLedgerIT.decisionRowDigestMatches…`).
     - Snapshot and record validate against the contract schema (`DecisionContractSchema.validateSnapshot/validateRecord`).
     - Same session gives the same ids and score bits (`RecommendationsEndpointIT.sameSessionAndContext…`), plus equal `candidate_set_digest` (`RecommendationLedgerIT.seedAndEnvironmentDigest…`).
   - Not proven: write order and failure paths (HIGH-3), and a stale snapshot (HIGH-2).
5. **Concurrency: genuine.** `AttemptConcurrencyIT` uses `ready` and `start` latches, a 20-thread pool and real HTTP. M01b produced five 201s, so a non-atomic implementation fails. The PATCH race (5 threads, start latch) caught M03.
6. **E2E:**
   - The "one canonical attempt" check does go through the API, but the double-click sends only one request (HIGH-1).
   - No fixed waits: no `waitForTimeout`, `setTimeout` or `sleep` in `test/e2e` or `apps/web/e2e`. Polling uses `expect.poll` and auto-waiting locators.
   - No assertions on constants found.
   - `apps/web/e2e/attempts.spec.ts` runs against MSW mocks, so it proves UI behaviour only.
7. **Handoff claims:**
   - The per-class counts in BC-0109, BC-0110, BC-0111 and BC-0113 all match today's XML. MigrationIT has grown from 13/14 to 15, as expected.
   - The jqwik try counts match.
   - The totals 232, 279 and 312 reproduce, but at `e958e22`, `babe75c` and `d8c8822` (LOW-10).
   - Web: 272 at the checkpoint plus 13 tests added after `d8c8822` gives 285, which matches today's run.
   - BC-0121: the artifacts exist, but "double submit proves one canonical attempt" is not supported (HIGH-1), and "10/10" is unverified.
8. **Coverage:** jacoco is absent. These production classes are never referenced by name in any test; they are exercised only through HTTP ITs:
   - **Attempts:** `AttemptCreateValidator`, `AttemptPatchValidator`, `AttemptFields`, `AttemptQueryParser`, `AttemptsController`, `RawJsonBody`, `TraceIds`, `AttemptBody`, `AttemptPatch`, `AttemptListView`.
   - **Recommendations:** `CandidateSnapshotJson` (its `verifyAgainstRanker` throw path is never triggered), `DecisionRecordJson`, `RecommendationValidator`, `ValidatedRequest`, `RecommendationsController`, `DecisionController`, `DecisionService`, `DecisionQueryRepository`, `DecisionView`.
   - **Catalog:** `RouteQueryParser`, `CatalogController`, `CatalogValidator`, `CatalogLineReader`, `RouteWriter`, `RejectionReport`, `CatalogImportRunner`.
   - **Identity:** `PreferencesValidator`, `PreferencesController`, `ConsentService`, `ConsentController`, `AuthController`, `MeController`, `BearerAuthenticationFilter`.
   - **Platform:** `ContentLengthLimitFilter`, `PathNormalizationFilter`, `RequestPaths`, `ApiExceptionHandler`, `ErrorResponseWriter`, `EnvelopeErrorController`, `Timestamps`, `JdbcTypes`, `StatusCodes`.
   - **Plain records and config:** (`*View`, `*Request`, `*Config`) excluded from concern.
   - **Top candidates for direct unit tests:** `CandidateSnapshotJson`, `RecommendationValidator`, `AttemptPatchValidator`, `AttemptQueryParser`.

## Missing tests over 30 minutes
- **Replay from a live decision** (est. 45 min): read `record_json` and the snapshot file, recompute `challenge_fit`, confidence and the score using `policy_config`, order by score then `TieBreak`, and assert ids, positions, score bits and reason codes. Covers MEDIUM-9.
- **Independent model in `FeatureRecomputePropertyTest`** (est. 60 min): keep a test-side map from `attempt_id` to the latest revision, built from the generated operations, and compare `ReferenceFeatures` over that model. Do not read the database back.
- **Concurrent hook race** (est. 60 min): two concurrent attempt writes for one user, then assert the final snapshot equals `recompute(max W)`. Depends on the HIGH-2 design decision.
- **Warm users in `RecommendationPropertyTest`** (est. 45 min): seed 0–5 attempts per user at random offsets and assert the cooldown and widening invariants through the service.
- **Direct unit tests** for `CandidateSnapshotJson`, including the disagreement throw path (est. 40 min).

## Missing tests under 30 minutes, in priority order (for the top-level session to implement)

1. **E2E double submit reaches the server** (`/Users/joshuawu/Beacon-Climb/test/e2e/slice.spec.ts` d1, ~25 min).
   - Record `page.on('request')` for `POST /v1/me/attempts` around the `dblclick`.
   - Read the browser's `Idempotency-Key` and body, and re-POST them via `api.post` with that key.
   - Assert 200, the same `attempt_id`, and still one item.
   - Optionally intercept with `page.route` and fulfil the first POST twice to force a real duplicate.
   - Covers HIGH-1.
2. **Older-`W` recompute must not regress the live snapshot** (new IT in `services/api/src/test/java/com/beaconclimb/api/features/`, ~15 min).
   - Same shape as the scratch probe: warm user, then `recompute(T-1d)`, then assert `find()` still has the newer `cutoff_w` and the recommendation has `cold_start` false.
   - It will fail until the guard or design decision lands, so mark it `@Disabled("BC-0113 follow-up")` only if the owner agrees. Covers HIGH-2.
3. **Quota reached at service level returns 503 and writes nothing** (`RecommendationLedgerIT` or a hand-wired service test like `RecommendationPropertyTest`, ~25 min).
   - Use `LocalFsSnapshotStore(tmp, 1)` pre-filled with one file.
   - Assert the response is 503 `SERVICE_UNAVAILABLE`, there are zero `recommendation_decisions` rows for the user, and no new file. Kills M11.
4. **Store digest mismatch returns 500 and no ledger row** (~20 min). Use a fake `SnapshotStore` whose `put` returns `"0".repeat(64)`, and assert the exception and zero decision or exposure rows. Kills M12.
5. **Ledger insert fails, so the new snapshot file is deleted** (~25 min). Use a `DecisionLedgerRepository` stub that throws in `insertExposures`, and assert `store.exists(digest)` is false afterwards and no decision row exists.
6. **Deterministic `ON CONFLICT` test** (`AttemptRepository`, ~15 min). Call `insertIfAbsent` twice with the same user and key and different `attempt_id`. Assert the second call returns false and does not throw, with one row. Covers MEDIUM-6.
7. **`attempt_revisions` is append-only for `beacon_app`** (`MigrationIT` or `AttemptPatchIT`, ~10 min). `UPDATE` and `DELETE` on a revision row must fail with a root cause containing "permission denied". Covers MEDIUM-7.
8. **Ranker with duplicate candidate ids** (`RankerTest`, ~10 min, needs an owner decision on dedupe vs reject).
   - Assert that `[a, a, b, c]` serves each id at most once.
   - Remove `uniqueElements` from one `RankerPropertyTest` arbitrary, or add a dedicated property. Covers MEDIUM-4.
9. **`FeatureComputer` duplicate and lower-revision rows** (`FeatureComputerTest`, ~15 min).
   - `[r, r]` must equal `[r]`, and `[rev2, rev1]` must equal `[rev2]`.
   - Either make `FeatureComputer` keep the highest revision per `attempt_id`, or document the precondition and assert it at entry. Covers MEDIUM-5.
10. **Five-term score bit fixture** (`RankerTest`, ~20 min). Add a table of 10–20 heuristic-v1 inputs with hard-coded expected `doubleToRawLongBits`, chosen among the order-sensitive combinations. One example from the off-line check: grade 10.0, target 11, novelty 0, preference 1, obs 2 gives `0.4800000000000001`, while the reversed sum gives `0.48000000000000004`. Makes M07 detection independent of a single witness.
11. **`differentSessionIdMayChangeTieOrderOnly` asserts the tie rule** (~10 min). For every position whose id differs between the two sessions, assert the scores are equal and the order follows `TieBreak.key` for each session.
