# ADR-0003 — Replay artifacts

**Status:** PROPOSED · **Task:** BC-0011 · **Date:** 2026-10-14

## Decision

A decision record references a content-addressed candidate snapshot. Both conform to `contracts/decisions/decision-record.v1.schema.json`. Use RFC 8785 JCS bytes (ADR-0002) for `candidate_set_digest=sha256(snapshot bytes)` and the stored file itself; `data/decisions/<first-two-hex>/<digest>.json`. `SnapshotStore.put(bytes)->digest`, `get(digest)->bytes`, `delete(digest)->void`; `put` verifies canonical bytes and atomic rename. `rng_seed=sha256(UTF-8(policy_version + "|" + user_id + "|" + session_id))`, lowercase hex.

Snapshot contains the request's frozen user feature vector, cutoff and schema, catalog version, all eligible candidates after hard filters, their exact scoring inputs and model outputs as served, and each hard-filter outcome. Ineligible candidates may be listed for audit but are not ranked. No notes, credentials or private raw attempt history. Record stores request constraints, policy version/config and code digest, model version/artifact digest or `none`, feature snapshot ID/as-of/schema, snapshot digest and URI, seed, assignment/served variants, ranked IDs/scores/reasons, explanation and environment digest. The complete synthetic 12-candidate fixture is under `contracts/decisions/examples/`. Its reference ranker is `heuristic-v1.cjs`: score = `0.4*(1-min(1,abs(grade_numeric-target_grade_numeric)/10)) + 0.15*novelty`; remaining weights are zero. Ties sort by ascending SHA-256 hex of `policy_version|user_id|session_id|route_variant_id`. The fixture record stores the SHA-256 digest of those policy source bytes and of canonical `synthetic-environment.json`; these are real fixture digests, not production implementation digests.

## Faithful replay

Read only the decision row and snapshot bytes. Verify digest before parsing; verify policy code digest resolves to a pinned immutable implementation. Execute that policy on frozen inputs in the specified arithmetic order and compare IDs, positions, IEEE-754 score bit patterns, tie order and reason codes. No tolerance and no model calls or live feature/catalog reads. If the policy code is unavailable or digest differs, return `POLICY_CODE_CHANGED` with `match=false`; do not claim a faithful match from a different implementation. Corrupt bytes → `DIGEST_MISMATCH`; missing file → `ARTIFACT_MISSING`; erased record → `REPLAY_UNAVAILABLE_ERASED`. A stored result comparison can still be inspected, but is not independent proof of recomputation. BC-0404 fixtures test all four states.

## What-if

`POST /v1/me/decisions/{id}/compare` freezes candidate IDs, eligibility and feature values. It may call an alternative model on those inputs and change policy weights. Output `semantics: COUNTERFACTUAL_RANKING`, rank/score/reason diffs and model/policy versions. Never call changed rank an observed user outcome. Tolerance applies only to alternative model score comparison: deterministic heuristic 0; CPU float model `abs(a-b) <= 1e-6 * max(1,abs(a),abs(b))`; probabilistic outputs use a model-card declared tolerance, default same 1e-6 relative. No tolerance in faithful mode.

## Erasure and retention

Hard erasure deletes candidate snapshot bytes and user-bearing record fields; retain `decision_id`, `erased=true` and minimal audit timestamps. Replay returns `REPLAY_UNAVAILABLE_ERASED`. BC-0412 checks both file deletion and response. Local disk artifacts require backup/restore policy before any live-user use.

## Amendment 2026-10-19 — heuristic-v2 and decision record v1.1 (BC-0210)

Owner decisions (chat, 2026-10-19): model outputs enter ranking through optional, additive v1.x fields rather than a v2 record; the grade is blended by ascent count; embeddings drive novelty and "more like this"; heuristic-v2 is the default and heuristic-v1 stays selectable per request (`policy_version`, contract 1.3.0).

- **Same schema file, v1.1, additive.** `candidate.model_outputs.grade_used` and `FullRecord.model_artifacts[] {role: grade|embedding, model_version, artifact_digest}` are optional; every v1.0 document stays valid. A heuristic-v1 decision writes neither, so its snapshot and record bytes are unchanged. `model_version` / `model_artifact_digest` name the grade model, or `"none"` / null when no prediction was read.
- **What is frozen.** The model is never called on the request path: predictions and embeddings are precomputed into `route_model_outputs` (V13) per registered version. The snapshot freezes, per candidate, `predicted_grade`, `embedding_distance` (smallest cosine distance to the user's recent routes) and `grade_used`, and `features.novelty` holds the v2 novelty. Faithful replay of a v2 decision re-derives `challenge_fit` from `grade_used` and the target grade, reads novelty from `features.novelty`, and sums the five products in the fixed order; `ModelAwareRecommendationsIT` asserts this bit for bit.
- **Formulas** (policy `heuristic-v2.json`): `grade_used = (n * grade_numeric + blend_k * predicted) / (n + blend_k)` with `n = grade_observation_count` and `blend_k = min_obs`; `novelty = 0` for a recent route, else `min(1, d / novelty_distance_scale)`, 1 without an embedding. Both parameters are untuned defaults (no real outcomes exist to tune against).
- **Environment.** `environment_digest` covers `ranking_implementation = java-heuristic-v2` for v2 decisions; v1 keeps `java-heuristic-v1`. `policy_code_digest` still hashes the whole ranking package, so it changed for both policies with this release, as for any ranking code change.
- **Known limit.** A snapshot lists every candidate. On the local Kilter catalog (about 55,000 variants at 40°) each snapshot is 26–28 MB and a recommendation takes about 2.2–2.4 s for v1 and v2 alike (measured locally 2026-10-19); the synthetic demo board is unaffected. Bounding the snapshot (e.g. a pre-filter by grade window, or listing only scored candidates with a digest of the rest) is a replay-contract decision for a later ticket.
