# Streaming: Kafka, Flink, materializer, reconciliation and parity (`make stream-*`)

**Ticket:** BC-0312 (Gate G3 review). **Written against:** HEAD 254b918 (2026-10-18). **Executed as
a whole: no.** Every step below is either the command a recorded proof ran or the Makefile target
that wraps it; the recorded runs are the BC-0303 compose proofs (`docs/evidence/BC-0303/`, commit
02aebb3, still with the placeholder operator) and the BC-0308 full-stack proof
(`docs/evidence/BC-0308/`). The restart, redrive, reconcile and parity sections have unit and
Testcontainers coverage (named per section) and are exercised against the live stack by the
BC-0307 chaos suite (`make test-chaos`, `test/chaos/README.md`), whose transcripts land in
`docs/evidence/BC-0307/`. Where a procedure has never been run on the stack this document says so.
All data on this stack is synthetic (`synthetic_*`, `e2e_*`, `demo`).

Every number in this file comes from a file under `docs/evidence/` or a row of
`docs/evidence-ledger.md`, with the path next to it. Anything without a path is configuration or a
qualitative description, not a measurement.

## 1. Purpose and trigger

Open this runbook when:

- you are bringing the stream profile up or down (`make stream-up`, `make stream-submit`,
  `make stream-down`, `make reset-db`) or adding the observability profile on top of it;
- a container restarted (api, Flink taskmanager or jobmanager, Kafka) and you need to know what
  recovered by itself and what needs `make stream-submit` again;
- a Prometheus rule from `docs/runbooks/observability.md` section 4 is firing
  (`FlinkCheckpointStale`, `FlinkSourceLagHigh`, `ConsumerLagHigh`, `MaterializerConflicts`,
  `DlqRate`, `FlinkDlqRate`, `OutboxBacklogStale`);
- `make dlq-status` shows records, `reconciliation_events` has open rows, or `make test-parity`
  exits 1;
- you want the freshness number (`make loadgen`) or the parity verdict for a Gate review.

### 1.1 Topology

```text
POST /v1/me/attempts ──► attempts + attempt_event_outbox (one transaction, api)
                              │  OutboxPublisher (SKIP LOCKED batches, at-least-once)
                              ▼
                        attempts.v1  (6 partitions, key = user_id, 7 d retention)
                              │  Flink job beacon-climb-features (services/stream, group beacon-stream)
                              │    parse + validate (services/events)  ──invalid──► stream-dlq.v1
                              │    ClimberFeaturesFunction keyed by user_id
                              │      late (occurred_at < watermark − 24 h) ──► stream-reconciliation.v1
                              ▼
                     climber-features.v1  (6 partitions, key = user_id, compacted)
                              │  api MaterializerListener (group beacon-materializer, manual ack after commit)
                              │    (partition, offset) guard ──conflict──► stream-dlq.v1
                              ▼
         user_feature_snapshots row (user_id, 1, 'STREAM')  ◄── parity oracle (`make test-parity`)
                                                              compares with a batch recompute at the row's cutoff_w

stream-reconciliation.v1 ──► api ReconciliationListener (group beacon-reconciliation)
                              ──► reconciliation_events (open rows) ──► `make reconcile`
                                  recomputes the SYNC row (user_id, 1, 'SYNC') at W = now

Read path (FeatureSelection, BC-0310 part 2): STREAM row when stream is enabled and
stream.cutoff_w >= sync.cutoff_w (ties to STREAM); otherwise SYNC with a persisted fallback reason.
```

Contracts: `contracts/events/README.md` (topics, keys, DLQ headers),
`contracts/features/climber-features.v1.md` (§6 snapshot columns and the coexistence primary key,
§10 the stream rules), `docs/adr/ADR-0004-stream-semantics.md` (the invariant and the decision
record). Module map: `services/api` (`outbox`, `materializer`, `reconciliation`, `parity`, `dlq`
packages), `services/stream` (the Flink job), `services/events` (validator), `services/features`
(the one `FeatureComputer` both sides use since commit 998e274).

## 2. Preconditions

- Docker Desktop (Compose v2) with enough memory for the profiles you load (section 2.2). The
  reference laptop in the ledger has 7836 MiB for containers (`docs/evidence-ledger.md`, Gate G1
  rows, Environment column).
- JDK 21 on the host (`mise.toml`; `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`
  on Homebrew macOS): `make stream-up` builds the api jar, `make stream-submit` builds the Flink
  fat jar, and `make test-parity`, `make reconcile`, `make dlq-*`, `make recompute-features` run
  the api jar on the host JVM.
- `.env` complete (`cp .env.example .env`, every value set); `GRAFANA_ADMIN_PASSWORD` only for
  the observability profile.
- Host ports free: 3000 (web), 8080 (api), 5432 (postgres), 9094 (Kafka host listener), 8081
  (Flink UI/REST); plus 9090, 3001, 3200 under observability. All bind `127.0.0.1` only.
- `python3` for `make loadgen` / `make freshness`.

### 2.1 Profiles and compose file sets

| Make target | Files merged (in order) | Profiles | What changes for the api |
|---|---|---|---|
| `make dev` | `docker-compose.yml` | `core` | `BEACON_STREAM_ENABLED=false`: no publisher, no materializer, no reconciliation consumer; read path serves SYNC with reason `STREAM_DISABLED` |
| `make stream-up` | + `kafka.yml` + `flink.yml` | `core`, `stream` | api recreated with `BEACON_STREAM_ENABLED=true`, `BEACON_STREAM_BOOTSTRAPSERVERS=kafka:9092`; Kafka, `kafka-init`, `flink-init`, `jobmanager`, `taskmanager` start |
| `make observability-up` | + `observability.yml` (last) | + `observability` | api and both Flink roles **recreated** with the OTel agent (running Flink job is cancelled); Prometheus, Grafana, Tempo, `otel-agent-init` |
| `make loadgen-stack [STREAM=0]` | stream set (or core with `STREAM=0`) + `loadgen.yml` (last) | as above | api recreated with raised write limits for a load run only |

Loading `kafka.yml` is the only publisher switch. Running `make dev` while the stream profile is
up recreates the api with the publisher and consumers off (and leaves Kafka/Flink running); run
`make stream-up` again to turn them back on.

### 2.2 Memory budget

`mem_limit` per service from the compose files; measured `docker stats --no-stream` from the two
recorded runs: `docs/evidence/BC-0303/docker-stats.txt` (core + stream, placeholder job, after the
restart proof) and `docs/evidence/BC-0308/docker-stats.txt` (core + stream + observability with
the OTel agent in the api and both Flink roles, after synthetic traffic). "one-shot" containers
exit before `docker stats` and have no measurement.

| Service | Profile | `mem_limit` | Measured, BC-0303 | Measured, BC-0308 (with agent) |
|---|---|---|---|---|
| `postgres` | core | 512m | 35.41 MiB | 42.33 MiB |
| `decisions-init` | core | 32m | one-shot | one-shot |
| `migrate` | core | 512m | one-shot | one-shot |
| `api` | core | 1g | 299.8 MiB | 572 MiB |
| `web` | core | 128m | 8.531 MiB | 8.57 MiB |
| `kafka` | stream | 1g (JVM heap `-Xmx512m`) | 382.2 MiB | 406.2 MiB |
| `kafka-init` | stream | 256m | one-shot | one-shot |
| `flink-init` | stream | 32m | one-shot | one-shot |
| `jobmanager` | stream | 768m (Flink process size 640m); **896m under observability** | 566.2 MiB of 768 MiB | 667.7 MiB of 896 MiB |
| `taskmanager` | stream | 1536m (Flink process size 1280m, 2 slots, parallelism 1) | 642.8 MiB of 1.5 GiB | 894.8 MiB of 1.5 GiB |
| `prometheus` | observability | 512m | – | 62.43 MiB |
| `grafana` | observability | 512m (`GOMEMLIMIT=384MiB`) | – | 339.2 MiB |
| `tempo` | observability | 512m | – | 239.3 MiB |
| `otel-agent-init` | observability | 128m | one-shot | one-shot |

Sums of the long-running limits: core 1664 MiB (api, postgres, web), stream 3328 MiB (kafka,
jobmanager, taskmanager; 3456 MiB with the jobmanager override), observability 1536 MiB. Measured
totals, adding the rows of each stats file: about 1.9 GiB for core + stream (BC-0303) and about
3.2 GiB for the full stack (BC-0308). Both runs completed without an OOM kill (the BC-0308 run
checked `docker compose ps -a` for exit 137; `docs/runbooks/observability.md` section 5). The
ADR-0004 fallback threshold is 4 GB for the Flink job plus the rest of compose; see the ADR's
decision record.

## 3. Steps

### 3.1 Bring-up

```sh
make stream-up          # api jar, then compose up -d --build --wait over the stream file set
make seed-demo          # synthetic board + demo users (idempotent); needed for attempts to exist
make stream-submit      # ./gradlew :stream:fatJar, then `flink run -d /opt/beacon/libs/stream.jar` in the jobmanager
make stream-job-status  # `flink list` in the jobmanager
```

Expected tail of `make stream-up` (`docs/evidence/BC-0303/compose-proof.txt`, step 1): every
container `Healthy` or, for the one-shots, `Exited`, then

```text
NAME                    STATUS                    PORTS
compose-api-1           Up 11 seconds (healthy)   127.0.0.1:8080->8080/tcp
compose-jobmanager-1    Up 11 seconds (healthy)   127.0.0.1:8081->8081/tcp
compose-kafka-1         Up 24 seconds (healthy)   127.0.0.1:9094->9094/tcp
compose-postgres-1      Up 24 seconds (healthy)   127.0.0.1:5432->5432/tcp
compose-taskmanager-1   Up 5 seconds (healthy)    6123/tcp, 8081/tcp
compose-web-1           Up 5 seconds (healthy)    127.0.0.1:3000->8080/tcp
```

`kafka-init` prints `--describe` for the four topics, each `PartitionCount: 6`, with
`retention.ms=604800000` (attempts), `cleanup.policy=compact` (features), `retention.ms=2592000000`
(DLQ and reconciliation). `flink-init` leaves `/checkpoints` owned by `9999:9999` (same file, step
1b).

Expected `make stream-submit` (same file, step 3):

```text
Job has been submitted with JobID 2ae3bea28220082b946b83cee9d8a73c
------------------ Running/Restarting Jobs -------------------
18.10.2026 06:11:12 : 2ae3bea28220082b946b83cee9d8a73c : beacon-climb-features (RUNNING)
```

Re-running `make stream-submit` submits a **second copy** of the job (both consume
`attempts.v1` under the same group and both write the sinks); `make stream-job-cancel` first.
`make stream-job-cancel [JOB=<id>]` cancels every RUNNING job (or one); checkpoints are retained on
cancellation (`execution.checkpointing.externalized-checkpoint-retention: RETAIN_ON_CANCELLATION`).

The job starts from the committed offsets of consumer group `beacon-stream`
(`OffsetsInitializer.committedOffsets(EARLIEST)`): a fresh broker log is read from the beginning,
a resubmitted job continues where the group last committed. Note what that does **not** carry
over: keyed state. See 3.3.

Add observability (`docs/runbooks/observability.md` for the detail):

```sh
make observability-up   # RECREATES api, jobmanager, taskmanager with the agent: the running job is cancelled
make stream-submit      # again
make observability-check
```

### 3.2 Teardown and reset

| Command | Effect |
|---|---|
| `make stream-down` | `down` over the stream file set: removes every core and stream container and the network; volumes kept (`pgdata`, `decisions`, `kafkadata`, `flink-checkpoints`). Observability containers are not in that file set: compose warns about orphans and leaves them running, so `make observability-down` first when the profile is up. |
| `make observability-down` | removes only `prometheus`, `grafana`, `tempo`, `otel-agent-init`; the api and Flink roles keep running **with the agent loaded** and an exporter that can no longer reach Tempo. `make stream-up` afterwards recreates them without the agent. |
| `make down` | core file set only: stops postgres/api/web, leaves Kafka and Flink running. Prefer `make stream-down` when the stream profile is up. |
| `make reset-db` | `down -v` over the **stream** file set: removes `pgdata`, `decisions`, `kafkadata` and `flink-checkpoints`. The broker log and the checkpoints go with the database on purpose: an outbox reset without the broker log would leave already-published events behind, and a checkpoint whose committed offsets no longer exist in the broker log cannot be restored. `init.sql` and `kafka-init` run again on the next `make stream-up`; the first job submission then reads an empty `attempts.v1`. The observability volumes (`prometheus-data`, `grafana-data`, `tempo-data`, `otel-agent`) survive; `docker volume rm compose_<name>` drops one. |

The compose project is named after the `infra/compose` directory, so every checkout of this
repository on one machine shares these volumes (section 6).

### 3.3 Restart procedures

What recovers by itself and what does not. `C` below is the stream compose prefix:

```sh
C="docker compose --env-file .env -f infra/compose/docker-compose.yml -f infra/compose/kafka.yml -f infra/compose/flink.yml --profile core --profile stream"
```

| Component | Command | Recovers automatically | Needs an operator |
|---|---|---|---|
| **api** | `$C restart api` | Outbox publisher resumes on the next poll; rows left unpublished by a crash mid-batch are re-sent with the same `event_id` (at-least-once; consumers dedupe on `(attempt_id, attempt_revision)`). Materializer and reconciliation consumers rejoin their groups and re-read from the last committed offset; a record that was applied but not yet acknowledged is redelivered and classified `DUPLICATE` (same offset, same checksum). Covered by `OutboxPublisherFaultIT`, `MaterializerListenerIT` (throw-after-commit + container restart), `ReconciliationListenerIT`. | Nothing. Do **not** use `make dev` to restart it (it switches the stream off, section 2.1). After any target that rebuilt the api jar, restart is **required** (section 6, "jar rebuilt"). |
| **Flink taskmanager** | `$C restart taskmanager` | The jobmanager's restart strategy (`fixed-delay`, 100 attempts, 5 s) redeploys the job from the latest completed checkpoint. Recorded: `docs/evidence/BC-0303/compose-proof.txt` step 7: `RESTARTING` at t+3 s and t+6 s, `RUNNING` at t+9 s with `restored_from_checkpoint: 6`, root exception `FlinkExpectedException`; the next event produced one more record (4 = 3 + 1, no reprocessing) and the committed offset of `attempts.v1` partition 3 moved 3 → 4. | Nothing, unless the taskmanager is down longer than 100 × 5 s; then the job is FAILED and needs `make stream-submit` (see jobmanager row for what that means). Watermarks are not checkpointed: the first event per key after a restore sets `W = occurred_at` (§10); the watermark re-forms from the following events. |
| **Flink jobmanager** | `$C restart jobmanager` | The session cluster comes back empty: no high-availability service is configured in `flink.yml`, so the running job is **lost**. The taskmanager re-registers its slots. | `make stream-submit`. A plain resubmission starts from the committed `beacon-stream` offsets with **empty keyed state**: the next event of a user is aggregated over that one event, so the STREAM row for that user drifts from the batch recompute until a rebuild republishes a newer sequence (no such rebuild exists yet, section 8). Flink can resume from a retained checkpoint (`flink run -d -s file:/checkpoints/<old job id>/chk-<n> /opt/beacon/libs/stream.jar` inside the jobmanager container); **this path has not been exercised in a recorded proof** and is not what `make stream-submit` does. |
| **Kafka** | `$C restart kafka` | The broker reopens `kafkadata` (fixed `CLUSTER_ID`). The api publisher fails its in-flight batch after `delivery-timeout` (10 s), rolls the rows back and retries on the next poll (`OutboxPublisherFaultIT`: broker paused during 100 POSTs, every POST 201, backlog drained after unpause). The api consumers reconnect. The Flink Kafka source throws, the job restarts under the restart strategy from the latest checkpoint. | Nothing by design; the live measurement is the `kafka-restart` scenario of BC-0307 (`docs/evidence/BC-0307/`). While the broker is down `make outbox-status` shows the backlog growing and `OutboxBacklogStale` fires after 60 s. |
| **api + Flink via `make observability-up`** | – | – | The api and both Flink roles are recreated (their environment changes) and the job is cancelled: `make stream-submit` again (`docs/runbooks/observability.md` 3.1). |
| **`make stream-up` on a running stack** | – | Only containers whose configuration changed are recreated. | If `services/api` changed, the api container keeps running the old JVM on a rewritten jar: `$C restart api` (section 6). The Flink jar is uploaded at `flink run`, so a rebuilt `stream.jar` only takes effect at the next `make stream-submit`. |

### 3.4 Checkpoint restore

Checkpoints are written every 10 s (`execution.checkpointing.interval: 10s`) to the named volume
`flink-checkpoints`, mounted at `/checkpoints` in both Flink roles, as
`/checkpoints/<job id>/chk-<n>/_metadata` next to `shared/` and `taskowned/`
(`docs/evidence/BC-0303/compose-proof.txt` step 6). `flink-init` chowns the volume to
`9999:9999` before either role starts; without it the CheckpointCoordinator fails with
`Failed to create directory for shared state` and the job never leaves INITIALIZING
(`infra/compose/flink.yml` comment, fix #1 of the proof).

See the latest completed and the latest restored checkpoint through the REST API:

```sh
JOB=$(curl -s http://127.0.0.1:8081/jobs | python3 -I -c 'import json,sys; print([j["id"] for j in json.load(sys.stdin)["jobs"] if j["status"]=="RUNNING"][0])')
curl -s http://127.0.0.1:8081/jobs/$JOB/checkpoints | python3 -I -c 'import json,sys; d=json.load(sys.stdin); print(json.dumps({"counts": d["counts"], "latest_completed": d["latest"]["completed"] and {k: d["latest"]["completed"][k] for k in ("id","status","external_path")}, "latest_restored": d["latest"]["restored"]}, indent=2))'
```

Expected before any restart (same file, step 6) and after the taskmanager restart (step 7):

```text
"counts": {"restored": 0, "total": 5, "completed": 5, "failed": 0}, "latest_completed": {"id": 5, "status": "COMPLETED", "external_path": "file:/checkpoints/2ae3bea28220082b946b83cee9d8a73c/chk-5"}, "latest_restored": null
"counts": {"restored": 1, "total": 7, "completed": 7, "failed": 0}, "latest_restored": {"id": 6, "external_path": "file:/checkpoints/2ae3bea28220082b946b83cee9d8a73c/chk-6"}
```

`latest_restored.id` is the checkpoint the running execution was restored from; `counts.restored`
counts restores since submission. The same numbers are on the Flink UI (`http://127.0.0.1:8081`,
job → Checkpoints) and, under observability, as
`flink_jobmanager_job_numberOfCompletedCheckpoints` (should climb about six per minute at the 10 s
interval; `FlinkCheckpointStale` fires when it stops).

List the volume from the host:

```sh
$C exec jobmanager ls -R /checkpoints | head -40
```

What a restore means for the data (`contracts/features/climber-features.v1.md` §10):

- Keyed state (`MapState<attempt_id, AttemptState>` per user) and the Kafka source offsets are in
  the checkpoint; the restored job re-reads from the checkpointed offsets, so events between the
  checkpoint and the failure are processed again. Duplicates and already-seen revisions change
  nothing in state and emit nothing; a record that was emitted before the failure may be emitted
  again at a newer offset, which the materializer applies (higher offset, same bytes) and parity
  still holds.
- **Watermarks are not checkpointed.** The first event a key sees after the restore has no
  watermark: `W = occurred_at` of that event. The lateness rule (`occurred_at < watermark − 24 h`
  goes to `stream-reconciliation.v1`) therefore cannot classify that first event as late; the
  watermark re-forms from the following events. ADR-0004: "a recovered job may briefly emit an
  older full snapshot at a newer offset; parity is asserted only after recovery has consumed the
  input through cutoff W and reconciliation completes."
- Checkpointing makes the Flink state and the source offsets consistent with each other; it does
  not make the writes to Kafka or to Postgres transactional (AGENTS.md "Streaming rigor"). The
  Kafka sinks and the materializer are at-least-once and idempotent by revision and offset.

### 3.5 DLQ: inspect and redrive

Full procedure, error codes and attempt-counter semantics: `docs/runbooks/dlq.md`.

```sh
make dlq-status                                            # count + last 20 records, oldest first
make dlq-redrive EVENTS=<event_id>[,<event_id>...] DRY_RUN=1  # audit lines only
make dlq-redrive EVENTS=<event_id>[,<event_id>...]           # republish the ORIGINAL bytes
```

Audit line per selected record, then a summary (fixed format):

```text
redrive event_id=<DLQ event_id> original_topic=<topic> original_key=<key> attempt=<n> result=PUBLISHED|SKIPPED|DRY_RUN[ reason=<why>]
redrive summary topic=stream-dlq.v1 scanned=<records read> selected=<n> published=<n> dry_run=<n> skipped=<n>
```

Exit 0 when every selected record was published (or dry run), 1 when any was `SKIPPED`
(`NOT_FOUND`, `DUPLICATE`, `SCHEMA_VIOLATION`, `NOT_JSON`, `PUBLISH_FAILED:<exception>`), 2 usage.
`EVENTS=ALL` selects every record. Never redrive `NOTES_PRESENT` (fix the producer; the bytes
contain the note). A redrive republishes on every run (at-least-once); the DLQ topic is append-only,
so the redriven records stay listed. Both targets rebuild the api jar first (section 6).

### 3.6 Reconcile late events

An `attempts.v1` event with `occurred_at` more than 24 h behind the Flink watermark is not applied
to the STREAM state; its original bytes go to `stream-reconciliation.v1` (headers
`reason=LATE_BEYOND_WINDOW`, `watermark=<W>`), the api's reconciliation consumer stores one
`reconciliation_events` row per `event_id` (a redelivery is a no-op), and the row stays open until
repaired. Recorded on the placeholder job: events dated four days before the run produced three
`stream-reconciliation.v1` records and no feature record
(`docs/evidence/BC-0303/compose-proof-late-path.txt`, steps 5 and 9).

```sh
make reconcile
```

Expected output (`ReconcileRunner`): one line per user with open rows and a summary:

```text
reconciled user_id=<uuid> rows=<n> cutoff_w=<W> checksum=<hex> resolution=RECONCILED|ALREADY_CURRENT
reconcile summary users=<n> rows=<n> reconciled=<n> already_current=<n> errors=<n>
```

- `ALREADY_CURRENT`: the recompute at `W = now` produced the checksum the SYNC row already carried.
  This is the common case: the api's post-commit hook recomputes the SYNC row from Postgres, where
  the "late" attempt was never late; the event is late only for the stream.
- `RECONCILED`: the SYNC row's checksum changed (or there was no SYNC row); the row now includes the
  late attempt.
- `ERROR` (exit 1): the row stays open for the next run; the line carries `error=<message>`.

**Which row wins afterwards.** `make reconcile` repairs the SYNC row only; the STREAM row still
lacks the late attempt. The read rule (`FeatureSelection`, BC-0310 part 2) serves the STREAM row
only when its `cutoff_w` is at least the SYNC row's; the repaired SYNC row carries `cutoff_w = now`
of the repair, which is newer than the STREAM row's `source_watermark`, so recommendations read the
repaired SYNC row (`feature_fallback_reason = STREAM_OLDER_THAN_SYNC` in the decision ledger) until
the stream emits a newer record for that user. A newer STREAM record then wins again **without** the
late attempt (section 8: no batch rebuild of the STREAM row exists yet). `make test-parity` on that
user reports `DIFF` with `checksum_differs: true`; `test/integration/parity/README.md` records that
as the expected outcome of the reconciliation scenario, not as a pass.

Exercised by `ReconciliationListenerIT`, `ReconcileServiceIT`, `ReconcileProfileIT` (headless boot)
and the BC-0307 scenario `late-3d-reconciliation`; not yet run by hand on the compose stack outside
that suite.

### 3.7 Parity (`make test-parity`)

The ADR-0004 oracle: for every non-erased user with a STREAM row (or `USER=<uuid>`), batch =
`FeatureRecomputeService.compute(user, W = stream.cutoff_w)` through the shared `FeatureComputer`,
compared with the stored canonical `aggregates_json` and `provenance_checksum`. Read-only
(`ParityServiceIT.oracleNeverWrites`), no Kafka, no Flyway. Mechanics and report fields:
`scripts/parity/README.md`; the per-scenario protocol: `test/integration/parity/README.md`.

```sh
make test-parity                                   # USER=all, OUT=docs/evidence/BC-0306/parity-<timestamp>.json
make test-parity USER=<uuid> OUT=<path>.json
```

Last line on stdout:

```text
parity summary users=<n> with_stream_row=<n> compared=<n> equal=<n> diffs=<n> same_w_mismatch=<n> skipped_no_stream_row=<n> synthetic_only=true out=<path>
```

Report (pretty JSON at `OUT`): `generated_at`, `commit`, the counts above, `details[]` with
`user_id`, `status` (`EQUAL` | `DIFF` | `SAME_W_MISMATCH` | `NO_STREAM_ROW`), `cutoff_w`,
`sync_cutoff_w`, `stream_feature_as_of`, `batch_feature_as_of`, `stream_partition`,
`stream_offset`, `diff_keys`, `checksum_differs`.

Exit codes: **0** iff `diffs == 0 && same_w_mismatch == 0` (a run with no STREAM row at all is 0
with `compared=0`, a vacuous pass); **1** any `DIFF` or `SAME_W_MISMATCH`; **2** bad argument,
failed run, or the **synthetic-only gate**: when any compared user is not a `synthetic_*`, `e2e_*`
or `demo` account no report is written and the count of such users goes to stderr. Only counts are
printed; user ids appear in the report file only.

Timing: parity holds only after the stream has consumed the input through `W` and reconciliation
has completed. Run the oracle after the stack has settled (materializer lag 0 on
`climber-features.v1`, every scenario user's STREAM `cutoff_w` at or past the last admitted event),
not during a burst. `feature_as_of` is reported but never compared (empty set: STREAM carries
`source_watermark`, SYNC stores null). Grade-derived keys can differ without a checksum change after
a catalog re-import; keep one catalog version per run.

No live report is committed by the implementation handoff (`docs/evidence/BC-0306/README.md`); the
first live runs are the BC-0307 scenario reports.

### 3.8 Load and freshness (`make loadgen-stack`, `make loadgen`)

```sh
make loadgen-stack            # stream file set + infra/compose/loadgen.yml (raised api write limits), --wait
make seed-demo
make stream-submit
make loadgen USERS=50 RATE=100 DURATION=300 OUT=reports/loadgen/target-100eps-5min
make freshness EVENTS=reports/loadgen/target-100eps-5min/events.jsonl   # recompute the summary from the stored events
```

`loadgen.py` registers `synthetic_<runid>_<i>` users, posts attempts at the global rate and polls
recommendations until each event is visible (`feature_as_of >= occurred_at`); **freshness =
`visible_at − occurred_at`** over visible events, nearest-rank p50/p95/p99, plus the
`features_source` distribution (a freshness number is a stream-path number only when the source is
`STREAM`). `summary.txt` records the hardware block and the api container's `BEACON_*` overrides so
a report says which limits were active. Definitions, rate-limit table and the ledger procedure:
`docs/benchmarks/README.md`; guards and caveats: `scripts/loadgen/README.md`. **No stream-profile
freshness measurement is recorded in `docs/evidence/` at the time of writing** (`docs/benchmarks/README.md`
"No measurement recorded yet"); do not quote one. `make dev` or `make stream-up` afterwards recreates
the api without the overrides.

### 3.9 Observability pointers

`docs/runbooks/observability.md`: dashboard **Beacon streaming** at `http://127.0.0.1:3001`
(rows: outbox, materializer outcomes and consumer lag, Flink uptime/checkpoints, freshness proxy,
DLQ writes), the eleven alert rules (no notification channel), TraceQL for a `POST /v1/me/attempts`
trace. Without the profile: `make outbox-status` (unpublished rows, oldest age, rows over 60 s),
`curl -s http://127.0.0.1:8080/prometheus | grep ^beacon_` (`beacon_outbox_*`,
`beacon_materializer_*`, `beacon_dlq_published_total`), the Flink UI on `127.0.0.1:8081`, and
`make dlq-status`.

## 4. Verification

The stack is healthy when all of the following hold:

```sh
$C ps -a                     # every long-running container (healthy), one-shots Exited (0)
make stream-job-status       # exactly one `beacon-climb-features (RUNNING)`
make outbox-status           # unpublished_rows 0 | oldest_unpublished_age_s 0 | unpublished_over_60s 0 (idle stack)
curl -s http://127.0.0.1:8081/jobs/$JOB/checkpoints | grep -o '"failed":[0-9]*'   # "failed":0, and completed climbing
make dlq-status              # dlq-status topic=stream-dlq.v1 records=0 (or only records you expect)
make test-parity             # parity summary ... diffs=0 same_w_mismatch=0 ..., exit 0 (compared > 0 to be meaningful)
```

Recorded reference: `docs/evidence/BC-0303/compose-proof.txt` (job RUNNING, 3 events in → 3
`climber-features.v1` records, checkpoints `completed: 5, failed: 0`, restore from checkpoint 6,
0 reconciliation records for on-time events) and `docs/evidence/BC-0308/metrics-presence.txt`
(four Prometheus targets up).

## 5. Rollback

- A bad job submission: `make stream-job-cancel`, fix, `make stream-submit`. Checkpoints are
  retained; the committed `beacon-stream` offsets are where the next job resumes.
- A bad api jar: rebuild and `$C restart api`; the outbox and the consumer groups resume from
  their committed positions.
- A redrive cannot be undone (the record is on `attempts.v1`); the consumers are revision-aware,
  so an already-applied record changes nothing (`docs/runbooks/dlq.md` section 5).
- A reconcile cannot be undone either, and does not need to be: it writes the SYNC row at
  `W = now` through the same service the attempts hook uses.
- Everything at once: `make stream-down` keeps the data; `make reset-db` drops it (section 3.2).

## 6. Troubleshooting

**`make stream-up` fails with `service "migrate" didn't complete successfully: exit 1`**, logs show
`FATAL: password authentication failed for user "beacon_migrate"`: a `compose_pgdata` volume from
another checkout or an older `.env` (the compose project is named after `infra/compose`, so all
clones on one machine share the volumes, and `make down`/`make stream-down` keep them). Run
`make reset-db` once, then `make stream-up`. Seen on the Gate G1 clean-clone run
(`docs/evidence/BC-0125/attempt-3-stale-volume.txt`). The same sharing applies to `kafkadata`
(a broker log from another checkout, with its committed offsets) and to `flink-checkpoints`.

**Job stuck in `INITIALIZING`/`RESTARTING`, jobmanager log `Failed to create directory for shared
state: file:/checkpoints/<job>/shared`**: the checkpoint volume is root-owned (a volume created
before `flink-init` existed, or `flink-init` did not run). `$C ps -a` must show
`compose-flink-init-1 Exited (0)`; `$C exec jobmanager ls -ld /checkpoints` must show `9999 9999`.
Otherwise `$C run --rm flink-init` or `make reset-db`.

**`kafka` never becomes healthy**: the healthcheck is a TCP probe of `127.0.0.1:9092` inside the
container (no JVM per probe, to keep inside the 1 GiB cap), 12 retries at 5 s after a 20 s start
period. `$C logs kafka`: a `CLUSTER_ID` mismatch means the `kafkadata` volume was formatted under a
different id (another checkout's `kafka.yml`, or an edited id): `make reset-db`. The KRaft broker
opens its listeners last, so the probe is a close readiness proxy; `kafka-init` retries its first
call up to 30 times, so a slightly early "healthy" is harmless.

**`make stream-job-status` prints `No running jobs`**: nothing was submitted, the jobmanager was
recreated (`make observability-up`, a jobmanager restart, `make stream-up` after a `flink.yml`
change) or the job FAILED after exhausting the restart strategy (`curl -s
http://127.0.0.1:8081/jobs/overview`, then `.../jobs/<id>/exceptions`). `make stream-submit`
(section 3.3 for what a cold resubmission does to keyed state).

**Two jobs RUNNING**: `make stream-submit` ran twice. `make stream-job-cancel JOB=<newer id>`.

**`/v1/...` requests hang or fail with `NoClassDefFoundError` after `make test-parity`, `make
reconcile`, `make recompute-features`, `make dlq-status`, `make dlq-redrive` or `make stream-up`**:
each of these runs `./gradlew :api:bootJar`, which rewrites `services/api/build/libs/api-0.1.0.jar`
underneath the running api container (bind-mounted read-only). A JVM whose jar changes fails lazy
class loading while `/health/ready` keeps passing (`docs/runbooks/dev-stack.md`). Fix: `$C restart
api`. Avoid it by running the scripts without the Gradle step when the jar is already current
(`bash scripts/parity/parity.sh ...`, `bash scripts/dlq-redrive.sh ...`, the jar with
`--spring.profiles.active=reconcile|recompute`); the chaos harness does this under
`CHAOS_INVOKE=direct` and otherwise restarts the api whenever the jar hash changed
(`test/chaos/README.md` "Jar hazard"). The Flink job is unaffected: `flink run` uploads the jar to
the jobmanager, so a rebuilt `stream.jar` under the mount changes nothing until the next submit.

**`MaterializerConflicts` / `make dlq-status` shows `CHECKSUM_CONFLICT`**: the same feature-topic
`(partition, offset)` arrived with a different `provenance_checksum`: a stream rebuild or a second
job writing the same topic. Check for two RUNNING jobs first; then `docs/runbooks/dlq.md` and a
parity run for the user.

**Materializer lag grows while the job is RUNNING**: `beacon_materializer_errors_total` rising means
the Postgres transaction fails and the record is redelivered without limit (intentional
at-least-once); the api log names the cause. A Postgres outage blocks the single consumer thread
until it is back.

**`FlinkCheckpointStale` while the job is RUNNING**: checkpoint volume ownership (above) or the
taskmanager is restarting in a loop (`$C logs taskmanager`). `failed` in the checkpoints REST
response names the count.

**`make reset-db` complains that a volume is in use**: a container from another checkout's project
is holding it. `docker ps --filter volume=compose_kafkadata`, stop it, retry.

## 7. Delivery semantics (what the stack guarantees)

Every hop is **at-least-once**: outbox → `attempts.v1` (a row is marked published only after every
send in the batch is acknowledged; a crash in between re-sends with the same `event_id`), Flink
source → sinks (checkpoints make state and offsets consistent; the Kafka writes are not
transactional with the checkpoint), `climber-features.v1` → Postgres (manual acknowledgement after
the commit; a crash in between redelivers), `stream-reconciliation.v1` → `reconciliation_events`
(same), DLQ writes (a crash between the DLQ write and the source acknowledgement writes twice) and
the redrive (every run republishes).

Duplicates and reorderings converge instead of being prevented: the Flink state keeps the highest
revision per `attempt_id` (a lower or equal revision, including a duplicate delivery, changes
nothing and emits nothing; a retraction is a tombstone at its revision); the materializer applies a
record only when its `(partition, offset)` is greater than the stored one for the same partition,
treats an equal offset with the same checksum as a duplicate, sends an equal offset with a different
checksum or a different partition to the DLQ, and never guards on the watermark alone; the
reconciliation consumer keys on `event_id`. The cutoff `W` is explicit on every record
(`source_watermark`, stored as `cutoff_w`), so "the same input set" is a checkable statement.

**The claim is parity, not a delivery guarantee.** What BC-0306 asserts, and BC-0307 measures after
each fault, is that the materialized STREAM row at its `cutoff_w` equals the synchronous batch
recompute from the canonical Postgres attempts at that cutoff, payload and checksum, once the
stream has consumed the input through `W` and reconciliation has completed. Nothing here claims
that a record is delivered once, that a snapshot is never emitted twice, or that the Postgres
writes are transactional with Flink's checkpoints.

## 8. What is NOT proven

Caveats carried from the ticket handoffs (`planning/handoffs/BC-0301.md` … `BC-0311.md`) and the
contract; none of these is covered by a recorded proof.

1. **Equal-millisecond hook race.** `FeatureHookRaceIT` (BC-0124c) proves that overlapping attempt
   writes for one user leave the SYNC snapshot at the maximum hook cutoff and equal to
   `recompute(maxW)`, with the cutoffs made distinct by the test clock. Two hooks whose `W = now`
   fall in the same millisecond are not covered (`docs/reviews/BC-0124-test-audit.md`, "Concurrent
   hook race").
2. **No Kafka JMX / broker metrics.** The observability profile scrapes the api, the Flink roles and
   Prometheus itself; consumer lag comes from the clients. Broker-side health (ISR, log size,
   request latency) is not observed.
3. **Effective lateness is up to about 48 h.** The lateness test is `occurred_at < watermark − 24 h`,
   and the watermark itself trails the newest `occurred_at` by the 24 h bounded out-of-orderness;
   an event can therefore be about 48 h behind the newest event seen and still be applied
   (`climber-features.v1.md` §10). The boundary is unit-tested
   (`ClimberFeaturesFunctionTest.latenessBoundary`) and exercised by the BC-0307 lateness
   scenarios, not otherwise measured.
4. **`feature_as_of` convention for the empty set.** A STREAM record with nothing included carries
   `feature_as_of = source_watermark` (the event schema requires a timestamp); the SYNC row stores
   null for the same set. Parity compares `aggregates` and `provenance_checksum` only; nothing
   compares `feature_as_of` across sources, and the contract leaves the API's exposed
   `feature_as_of` different between the two sources for an empty set.
5. **No batch rebuild of the STREAM row after reconciliation.** `make reconcile` repairs the SYNC
   row only. The STREAM row keeps lacking the late attempt until the stream emits a newer record
   for that user, and that record still lacks it (the event was never applied to Flink state).
   ADR-0004's "a batch rebuild publishes a newer sequence" is not implemented; the read rule's
   preference for the fresher `cutoff_w` is what keeps the repaired SYNC row in front until then.
   The same gap applies to a cold job resubmission (section 3.3, jobmanager row).
6. **Observability part 2 pending.** No `request_id` / `event_id` span attributes, no
   `http.server.requests` histogram buckets (latency panels are average and max, no p99 alert),
   no server-side freshness gauge (the dashboard's freshness panel is the applied-vs-published
   proxy), and `beacon_dlq_published_total` / `beacon_materializer_invalid_total` register on first
   use (`docs/runbooks/observability.md` section 7).
7. **Not yet run on the compose stack by hand:** `make dlq-status`, `make dlq-redrive`,
   `make reconcile` and the `flink run -s <checkpoint>` resume. Their integration tests boot the
   same profiles against Testcontainers; the live exercise of the first three is the BC-0307 suite.
8. **No stream-profile freshness number** (section 3.8), and no committed live parity report at
   the time of writing (`docs/evidence/BC-0306/README.md`); the BC-0307 run supplies the first.
9. **The recorded compose proofs predate the real operator.** `docs/evidence/BC-0303/` was taken at
   commit 02aebb3 with `PlaceholderFeatures` (empty aggregates, empty-set checksum); it proves the
   engine, the connector, checkpoint restore and the late path on real Kafka, not the aggregation.

## 9. Evidence

| File | What it shows |
|---|---|
| `docs/evidence/BC-0303/compose-proof.txt` | 2026-10-18, commit 02aebb3, docker 29.2.1 / Compose v5.0.2, images `flink:2.2.1-java21` and `apache/kafka:4.3.1`: stack healthy, four topics × 6 partitions, job RUNNING, 3 events → 3 records, checkpoints `completed: 5`, taskmanager restart → RUNNING at t+9 s restored from checkpoint 6, 4 = 3 + 1 records afterwards with no reprocessing, 0 reconciliation records, `docker stats` |
| `docs/evidence/BC-0303/compose-proof-late-path.txt` | same day: three events dated 2026-10-14 (before the watermark) → 0 feature records, 3 `stream-reconciliation.v1` records |
| `docs/evidence/BC-0303/docker-stats.txt` | core + stream memory (section 2.2) |
| `docs/evidence/BC-0303/pinned-coordinates.txt` | the Maven coordinates and image, verified with HTTP 200 on repo1.maven.org |
| `docs/evidence/BC-0308/docker-stats.txt` | full-stack memory with the OTel agent (section 2.2) |
| `docs/evidence/BC-0308/metrics-presence.txt`, `targets.txt`, `prometheus-rules.txt` | metric names present, four targets up, 11 rules ok |
| `docs/evidence/BC-0307/` | the chaos suite's transcripts, per-scenario parity reports and `summary.md` (being produced by `make test-chaos` at the time of writing) |
| `docs/evidence/BC-0306/README.md` | how to read a parity report; no live report committed by the implementation handoff |
