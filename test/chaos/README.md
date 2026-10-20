# test/chaos — fault scenario suite for the streaming path (BC-0307)

`make test-chaos [SCENARIOS=a,b] [PARITY=skip]` runs `test/chaos/stream/run.sh`: one script per
fault in `scripts/chaos/<scenario>.sh`, a fresh `synthetic_*` user per scenario, and after each
scenario the same four harness assertions. It is the end-to-end half of ADR-0004 "Failure cases to
test"; the unit/integration half lives in the ITs named below. Evidence of every run:
`docs/evidence/BC-0307/` (`summary.md` with the hardware block and one row per scenario,
`<scenario>.txt` transcripts, `parity-<scenario>.json` oracle reports, `harness.txt`,
`docker-stats.txt`). Everything is measured; nothing in those files is typed by hand.

## What runs where

| piece | path | role |
|---|---|---|
| harness | `test/chaos/stream/run.sh` | preconditions, scenario loop, convergence, parity, lost-attempt and DLQ checks, summary |
| library | `test/chaos/stream/lib.sh` | compose/psql/Kafka-CLI/Flink-REST helpers, the contract checksum re-derived in SQL, waits |
| api client | `test/chaos/stream/api.py` | stdlib `python3 -I`: register + consent + preferences, POST/PATCH attempts with Idempotency-Key / If-Match, recommendations, the retrying burst |
| scenarios | `scripts/chaos/*.sh` | one fault each; idempotent (every run makes its own user), runnable alone |

Preconditions the harness enforces: `.env`, Java 21, the api answering on `http://127.0.0.1:8080`
with the stream profile (otherwise `make stream-up`), a RUNNING Flink job (otherwise `make
stream-submit`), routes seeded (otherwise `make seed-demo`), and `scripts/parity/parity.sh`
unless `PARITY=skip`.

Guards (code, not convention): the api client refuses any base URL host other than `127.0.0.1`
and any username not matching `^synthetic_[a-z0-9_]*$`; it never sends DELETE or anything under
`/v1/admin`; the library re-checks in Postgres that every user id a scenario hands over is a
`synthetic_*` account before parity, psql or Kafka manipulation touches it; tokens live only in a
0700 work directory deleted on exit and are never printed; the parity oracle itself refuses
non-synthetic users (BC-0306).

## Harness assertions after every scenario

1. **Convergence** (the "settle" step of `test/integration/parity/README.md`, bounded by
   `CHAOS_CONVERGE_TIMEOUT_S`, default 120 s): `attempt_event_outbox` has no unpublished row, the
   `beacon-stream` (Flink, attempts.v1) and `beacon-materializer` (climber-features.v1) consumer
   groups both report lag 0 (`kafka-consumer-groups.sh` inside the broker), the user's STREAM row
   exists with `cutoff_w >=` the last admitted event's `occurred_at`, and its
   `provenance_checksum` equals the contract checksum (`climber-features.v1.md` §6) re-derived
   in SQL over the canonical attempts at that W: ACTIVE rows with `occurred_at <= cutoff_w`,
   `"<attempt_id>:<revision>"` sorted in byte order, joined by `,`, sha256 (minus attempts the
   scenario declares the stream must lack).
2. **Parity oracle**: `make test-parity USER=<uuid> OUT=docs/evidence/BC-0307/parity-<scenario>.json`
   must exit 0 with status `EQUAL` (`PARITY_MODE=zero`), or exit 1 with `DIFF` and
   `checksum_differs: true` when the scenario declares `expect-diff`. `PARITY=skip` records
   "skipped" instead; such a run is **mechanics only** and says so in `summary.md`.
3. **No canonical attempt lost**: the count of ACTIVE attempts of the user in Postgres equals what
   the scenario expects, and every attempt id the scenario created exists at its expected final
   `revision` and `status`.
4. **DLQ growth**: the sum of `stream-dlq.v1` end offsets grew by exactly what the scenario
   expects (0 everywhere except `invalid-events-to-dlq`, 2).

After the loop the oracle runs once over **all** users with a STREAM row
(`parity-all-users.json`, exit 0 required) and `docker stats --no-stream` is recorded. Exit 1 if
any scenario or the final parity failed.

## Scenarios

| scenario | fault injected | asserted by the scenario itself (then the four harness checks) | proven elsewhere |
|---|---|---|---|
| `dup-publish` | 5 attempts; `published_at = NULL` on 3 outbox rows so the BC-0301 publisher re-sends the identical bytes (the real at-least-once path) | 3 rows re-published; attempts.v1 holds 5 + 3 records keyed by the user; Flink `attempts_stale_revision` advanced by >= 3 when exposed via REST; STREAM row byte-identical before/after (cutoff_w, checksum, partition, offset) | `ClimberFeaturesFunctionTest` (stale revision ignored), `MaterializerServiceIT` (DUPLICATE on equal offset) |
| `late-2h-inside-window` | 3 attempts now, 1 at now − 2 h (inside watermark − 24 h) | the STREAM row moves to `cutoff_w` = the late `occurred_at` (W = max(watermark, occurred_at), §10) and includes only the attempt(s) at or before that W (`attempts_90d` 1); the oracle is EQUAL at that W; a 5th attempt brings W forward and the row covers all 5 (`attempts_90d` 5); 0 reconciliation rows | `ClimberFeaturesFunctionTest.latenessBoundary` |
| `late-3d-reconciliation` | 2 attempts now, 1 at now − 3 d (beyond watermark − 24 h) | a `reconciliation_events` row (LATE_BEYOND_WINDOW, open) within 60 s; STREAM row unchanged; **the oracle reports DIFF** (exit 1, `checksum_differs`) and the DIFF is exactly the late attempt (batch checksum at the STREAM W minus the late id == STREAM checksum); `make reconcile` closes the row (RECONCILED / ALREADY_CURRENT), SYNC covers all 3 with `cutoff_w >= resolved_cutoff_w`; `POST /v1/me/sessions/recommendations` returns `features_source: SYNC` and `features_cutoff_w >=` the reconcile W; **SYNC-view parity**: `make recompute-features USER=<id> W=<SYNC cutoff_w>` prints the SYNC checksum and leaves `aggregates_json` byte-identical; clean-up: the late attempt is retracted (revision 2, also late, reconciled again) so the ACTIVE set equals what the STREAM row holds and the harness parity for the user is `zero` | `ReconciliationListenerIT`, `ReconcileServiceIT` |
| `correction-then-retraction` | PATCH sent=false (rev 2), PATCH retract (rev 3) | STREAM row follows each revision and ends at the empty set: checksum `e3b0c442…b855`, `attempts_90d` 0, `last_session_at` null, `recent_variant_ids` [], `feature_as_of == cutoff_w` (STREAM) while SYNC stores null; row is `3:RETRACTED`, 0 ACTIVE | `ClimberFeaturesFunctionTest`, `FeatureRecomputeIT` |
| `api-sigkill-during-burst` | burst of 50 attempts; `docker compose kill -s SIGKILL api` 0.5 s in; `up -d --wait api` | every key ends 201 or 200 (replay with the SAME Idempotency-Key after a lost response); 50 attempt rows == 50 distinct keys; backlog drains; records retried keys / connection errors / the outage window seen by the client | commit-before-ack interleaving: `MaterializerListenerIT` (BC-0305); idempotent create: `AttemptCreateIT` |
| `api-sigkill-after-publish` | 50 attempts all 201; SIGKILL the instant `beacon_outbox_backlog_rows` reads 0 on `/prometheus`; restart | 50 rows; backlog 0; STREAM row converges; materializer position at kill vs after | as above |
| `flink-taskmanager-restart` | 10 attempts; wait for a new completed checkpoint; `docker compose restart taskmanager`; 5 more | job RUNNING with `latest.restored.id` set (recorded); STREAM row covers 15 | BC-0303 compose proof (offset recovery) |
| `kafka-restart` | 5 attempts; `docker compose stop kafka`; 5 attempts during the outage; `start kafka` | 5 × 201 while the broker is down; unpublished outbox rows for the user >= 1 at that moment; broker healthy; backlog 0; job RUNNING (restart count recorded); STREAM row covers 10 | `OutboxPublisherFaultIT` (BC-0301) |
| `same-watermark-correction` | 1 attempt, PATCH sent=false at once (same `occurred_at`) | `cutoff_w` equal before/after, `source_offset` strictly greater, checksum `<id>:1` → `<id>:2`, `sends_90d` 1 → 0, same partition | `MaterializerServiceIT` (offset guard, no watermark guard) |
| `invalid-events-to-dlq` | an attempts.v1 record with `attributes.notes` and a climber-features.v1 record with a malformed `provenance_checksum`, produced straight into the topics keyed by the user | exactly 2 `stream-dlq.v1` records keyed by the user: `attempts.v1 NOTES_PRESENT` (consumer `stream`) and `climber-features.v1 SCHEMA_VIOLATION` (consumer `api`); no STREAM row from the junk; a real attempt converges afterwards; `make dlq-redrive EVENTS=<2 ids> DRY_RUN=1` prints 2 `result=DRY_RUN` lines and `published=0` | **CHECKSUM_CONFLICT** (equal offset, different checksum) cannot be provoked from outside the broker — offsets are assigned by Kafka, so any hand-made record arrives at a higher offset and is correctly applied; it is proven by `MaterializerListenerIT` |

Honest names: the ADR's "materializer crash before/after commit" needs a hook inside the api that
the compose api does not have, so the two SIGKILL scenarios kill the whole api container at two
points of the pipeline (during the burst; right after the publisher's last commit). They prove
end-to-end convergence after a crash, not a specific interleaving. The ADR's "idle partition" is
exercised implicitly: every scenario's user lands on one of six partitions while the others are
idle, and `withIdleness(1m)` keeps the watermark moving; no separate scenario claims more.

## Running one scenario

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
make stream-up && make stream-submit        # once; make seed-demo if the board is not imported
make test-chaos SCENARIOS=same-watermark-correction
make test-chaos SCENARIOS=dup-publish,kafka-restart PARITY=skip   # mechanics only, no oracle
bash scripts/chaos/dup-publish.sh           # standalone: the scenario's own assertions only,
                                            # result file printed to a temp dir, no harness checks
```

Knobs: `CHAOS_BASE_URL` (must be `http://127.0.0.1:<port>`), `CHAOS_FLINK_URL`,
`CHAOS_CONVERGE_TIMEOUT_S`, `CHAOS_INVOKE=make|direct` (below).

## Jar hazard (`CHAOS_INVOKE`)

`make test-parity`, `make reconcile`, `make recompute-features` and `make dlq-redrive` all start
with `./gradlew :api:bootJar`, which rewrites `services/api/build/libs/api-0.1.0.jar` — the file
bind-mounted read-only into the running api container. `docs/runbooks/dev-stack.md`
("requests hang after make import…") documents that a JVM whose jar changes underneath it fails
lazy class loading. The harness therefore hashes the jar around every `make` call; when the hash
changed it logs a WARNING and restarts the api so the stack runs the jar on disk again. With
`CHAOS_INVOKE=direct` the harness runs the same jar profiles the targets run
(`scripts/parity/parity.sh`, `--spring.profiles.active=reconcile|recompute`,
`scripts/dlq-redrive.sh`) without the Gradle step; use it when `services/api` is being edited
concurrently, and say so in the run (the summary header records the mode).

## Reading `summary.md`

One `## Run <id>` section per invocation: commit, `PARITY` and `CHAOS_INVOKE`, the hardware block
(same fields as `scripts/loadgen`), then one row per scenario — result, wall-clock runtime of the
scenario including the harness checks, convergence time, parity verdict, ACTIVE attempts
expected/found, DLQ growth observed/expected, notes — and one row for the final all-users parity.
A `PARITY=skip` run is labelled "mechanics only" and proves the harness and the scenarios drive the
stack as described; it is not a parity claim.
