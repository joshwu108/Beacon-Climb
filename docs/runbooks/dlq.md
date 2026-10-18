# DLQ: inspect, fix and redrive `stream-dlq.v1`

**Last real run:** 2026-10-18, working tree of BC-0302 (uncommitted at the time of writing): the Testcontainers integration tests `DlqPublisherIT` and `DlqRedriveIT` in `services/api`, which exercise exactly the classes behind `make dlq-status` and `make dlq-redrive` (including a boot of the `redrive` profile itself) against an `apache/kafka:4.3.1` broker. The Makefile targets have **not** yet been run against the compose stack (`make stream-up`); the "Evidence" section says what was observed and where. All data involved was synthetic.

## 1. Purpose and trigger

Open this runbook when:

- the Flink job's `attempts_invalid` counter or the api's `beacon_dlq_published_total` grows, or a log line `attempts.v1 record rejected ... dlq_event_id=...` / `dlq outcome=PUBLISHED ...` appears;
- a producer bug or contract change has been fixed and the quarantined events must be fed back;
- a user asks why an attempt is missing from their stream-derived features while it exists in the api.

What lands in the DLQ and why (ADR-0004, `contracts/events/README.md` "DLQ records, headers and redrive"): every consumer validates each record with the shared library `services/events` (`com.beaconclimb.events.EventValidator`), the same code that validates outbox payloads before an attempt is written. A record that fails goes to `stream-dlq.v1` untouched (base64 of the ORIGINAL bytes) with one of these `error_code`s:

| `error_code` | Meaning | Typical cause | Redrivable after a fix? |
|---|---|---|---|
| `NOT_JSON` | the bytes are not one well-formed JSON document (parse error, duplicate keys, trailing data) | corrupt or foreign producer, a manual `kafka-console-producer` test | no: the bytes themselves are wrong; manual review |
| `NOTES_PRESENT` | a member called `notes` appears at any depth | a producer leaked free text (privacy guard, C-01 / TM-11) | **never**: fix the producer; the record is deleted with the topic retention, do not redrive |
| `UNKNOWN_ATTRIBUTE` | `attributes` carries a key outside the closed allowlist | a producer emitted a new attribute before the consumer-first schema bump shipped | yes, once the consumer schema (and so this library) accepts the key |
| `SCHEMA_VIOLATION` | anything else the schema rejects (missing member, wrong type, bad `date-time`, unknown top-level member, non-object document) | producer bug, schema drift | yes if the consumer side was wrong, otherwise manual review |
| `UNKNOWN_TOPIC` | no contract registered for the source topic | misconfigured consumer | no |
| `CHECKSUM_CONFLICT` | (materializer, BC-0305) same feature-topic offset, different `provenance_checksum` | duplicate delivery after a stream rebuild | manual review (parity investigation, ADR-0004) |

Permanently invalid data is reviewed by a person and left to expire (30-day retention); it is never "fixed" by editing the bytes, because a redrive must republish the original bytes (ADR-0004).

## 2. Preconditions

- Docker Desktop with the stream profile running: `make stream-up` (Kafka host listener `127.0.0.1:9094`), and the Flink job submitted (`make stream-submit`) if you want redriven records consumed.
- `.env` in the repo root (the redrive reads only `BEACON_STREAM_BOOTSTRAPSERVERS` from it, if set; the database passwords are not exported to the JVM).
- Java 21 (`mise.toml`): `export JAVA_HOME=...` as for `make recompute-features`.
- **Authorisation, honestly:** `make dlq-status` and `make dlq-redrive` are operator CLIs on the laptop. Whoever can run the script with the repo's `.env` can read the DLQ and republish into `attempts.v1`; there is no HTTP endpoint, no role check and no audit beyond stdout. Pipe the output into the incident notes. Topic ACLs are a deployment concern that compose does not model (`contracts/events/README.md`).
- The redrive never touches Postgres: the `redrive` Spring profile is a separate context without a datasource (`com.beaconclimb.api.dlq.DlqRedriveApplication`).

## 3. Steps

### 3.1 Inspect

```sh
make dlq-status
```

Expected output: the record count and the last 20 records (oldest `failed_at` first), one per line:

```text
dlq-status topic=stream-dlq.v1 records=6
dlq event_id=01a14dbf-d2ad-7aa2-b470-2f6861da56ac error_code=NOTES_PRESENT attempt=1 original_topic=attempts.v1 failed_at=2026-10-18T06:42:59.372Z partition=0 offset=0
dlq event_id=01a14dbf-d5a5-7070-b4e8-1c3ed6c10c2f error_code=UNKNOWN_ATTRIBUTE attempt=1 original_topic=attempts.v1 failed_at=2026-10-18T06:43:00.133Z partition=0 offset=1
...
```

A line ending in `conforming=false` is a DLQ record that itself violates `stream-dlq.v1`; it can never be redriven (a redrive refuses to guess) and is manual-review only. `records=0` means the DLQ is empty.

To see the full value of one record (base64 of the original bytes, error message) use the broker's console consumer; it prints everything on the topic, including a rejected `notes` value, so do this only with synthetic data:

```sh
docker compose --env-file .env -f infra/compose/docker-compose.yml -f infra/compose/kafka.yml --profile core --profile stream \
  exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:9092 --topic stream-dlq.v1 --from-beginning --timeout-ms 5000 --property print.headers=true
```

### 3.2 Fix

Decide per `error_code` using the table above. The fix is always upstream or in the consumer (producer code, a consumer-first schema bump shipped through `services/events`, the materializer); the DLQ bytes are never edited. When the consumer was wrong, deploy the fixed consumer first (`make stream-submit` after `make stream-job-cancel`, or the api), then redrive.

### 3.3 Redrive

Dry run first; it prints the audit lines and publishes nothing:

```sh
make dlq-redrive EVENTS=01a14dbf-d5a5-7070-b4e8-1c3ed6c10c2f,01a14dbf-d9ea-7bee-b35e-5858e95c124a DRY_RUN=1
```

Then for real:

```sh
make dlq-redrive EVENTS=01a14dbf-d5a5-7070-b4e8-1c3ed6c10c2f,01a14dbf-d9ea-7bee-b35e-5858e95c124a
```

`EVENTS=ALL` selects every record on the topic (use with a dry run first). `TOPIC=<name>` reads another DLQ topic; without it only `stream-dlq.v1` is read, and a `beacon.stream.topics.dlq` that is not `stream-dlq.v1` is refused (exit 2) rather than silently used.

One audit line per selected record, fixed format:

```text
redrive event_id=<DLQ event_id> original_topic=<topic> original_key=<key> attempt=<n> result=PUBLISHED|SKIPPED|DRY_RUN[ reason=<why>]
redrive summary topic=stream-dlq.v1 scanned=<records read> selected=<n> published=<n> dry_run=<n> skipped=<n>
```

`attempt=<n>` is the attempt number of the republished record (the DLQ record's `attempt` + 1). `reason` appears only on `SKIPPED`: `NOT_FOUND` (id not on the topic), `DUPLICATE` (the same `event_id` appeared twice on the DLQ, an at-least-once double write; it was republished once), `SCHEMA_VIOLATION` / `NOT_JSON` (the DLQ record itself does not conform), `PUBLISH_FAILED:<exception>`.

Exit codes: 0 when every selected record was published (or dry run); 1 when any was skipped; 2 usage error.

### 3.4 Attempt counter semantics

- A record that fails for the first time is stored with `attempt=1` (value and `dlq.attempt` header).
- The redrive republishes the original bytes and key with the DLQ record's headers, `dlq.attempt` = previous + 1 and `dlq.redriven_from` = the DLQ record's `event_id`.
- If it fails again, the consumer reads `dlq.attempt` and the new DLQ record carries that number (`attempt=2`), with a new `event_id`; `dlq.redriven_from` is not copied onto the new DLQ record (the audit line already links them).
- There is no automatic retry and no attempt cap: every redrive is an operator decision.

## 4. Verification

After a real redrive:

```sh
make dlq-status            # the redriven records are STILL listed: the DLQ is append-only
make stream-job-status     # job RUNNING
```

and, for an `attempts.v1` redrive, watch the Flink job consume it: the `attempts_parsed` counter rises by the number of published records and no new DLQ record appears for the same original bytes (`make dlq-status` count unchanged). If the count rises, the fix did not cover the record: read the new line's `error_code`, and note that its `attempt` is now 2.

## 5. Rollback

A redrive cannot be undone: the republished record is in `attempts.v1` and will be consumed (at-least-once, ADR-0004). The consumers are revision-aware and idempotent by contract (`(attempt_id, attempt_revision)` dedupe), so redriving a record that was in fact already applied has no effect on the features; redriving a record that is still invalid only produces a new DLQ record with `attempt` + 1. To stop the effect of a redrive, cancel the job (`make stream-job-cancel`) before it consumes the record, fix, and resubmit; the record stays in the topic.

## 6. Caveats

- **At-least-once everywhere.** DLQ writes are retried by the failing consumer (a crash between the DLQ acknowledgement and the source ack writes the record twice; the redrive reports the copy as `DUPLICATE`). The redrive itself republishes on every run you execute: running `make dlq-redrive` twice with the same `EVENTS` publishes twice (`DlqRedriveIT` proves it), and a publish failure after some records were already sent leaves those published (exit 1, rerun only the `SKIPPED` ids).
- **The DLQ holds the rejected bytes.** A `NOTES_PRESENT` record contains the note. Treat the topic as sensitive; it has a 30-day retention and no other cleanup. Keep it synthetic-only until the data-rights review (`docs/data-source-audit.md`) clears real user data for the stream profile at all.
- **No automatic redrive, no HTTP endpoint, no RBAC** (section 2).
- **Flink restart after a redrive:** Flink watermarks are not checkpointed (BC-0303 handoff), so a redriven record that is older than the 24 h lateness window goes to `stream-reconciliation.v1`, not to the features; that is the documented late path, not a DLQ problem.

## 7. Evidence

2026-10-18, BC-0302 working tree, `./gradlew :api:test --tests '*DlqRedriveIT*' --tests '*DlqPublisherIT*'` against Testcontainers `apache/kafka:4.3.1` (6 tests, 0 failures; the full-suite totals are in `planning/handoffs/BC-0302.md`). Captured stdout of the in-process run of the `redrive` profile (`DlqRedriveApplication.run`, no datasource) inside `DlqRedriveIT`:

```text
redrive event_id=01a14dbf-d9ea-72aa-bc98-1c113eafac98 original_topic=attempts.v1 original_key=018f0000-0000-7000-8000-0000000000a1 attempt=2 result=DRY_RUN
redrive summary topic=stream-dlq.v1 scanned=6 selected=1 published=0 dry_run=1 skipped=0
dlq-status topic=stream-dlq.v1 records=6
dlq event_id=01a14dbf-d2ad-7aa2-b470-2f6861da56ac error_code=NOTES_PRESENT attempt=1 original_topic=attempts.v1 failed_at=2026-10-18T06:42:59.372Z partition=0 offset=0
dlq event_id=01a14dbf-d5a5-7070-b4e8-1c3ed6c10c2f error_code=UNKNOWN_ATTRIBUTE attempt=1 original_topic=attempts.v1 failed_at=2026-10-18T06:43:00.133Z partition=0 offset=1
dlq event_id=01a14dbf-d5b1-77d7-afeb-d90d3f0b5d5a error_code=UNKNOWN_ATTRIBUTE attempt=2 original_topic=attempts.v1 failed_at=2026-10-18T06:43:00.145Z partition=0 offset=2
dlq event_id=01a14dbf-d9ea-7bee-b35e-5858e95c124a error_code=NOT_JSON attempt=1 original_topic=attempts.v1 failed_at=2026-10-18T06:43:01.226Z partition=0 offset=3
dlq event_id=01a14dbf-d9ea-77ca-af36-da64ab3418e3 error_code=NOTES_PRESENT attempt=3 original_topic=attempts.v1 failed_at=2026-10-18T06:43:01.226Z partition=1 offset=0
dlq event_id=01a14dbf-d9ea-72aa-bc98-1c113eafac98 error_code=NOT_JSON attempt=1 original_topic=attempts.v1 failed_at=2026-10-18T06:43:01.226Z partition=5 offset=0
```

The same test asserted that the two selected originals reappeared on `attempts.v1` byte-identical with `dlq.attempt` 1 -> 2 and 3 -> 4, `dlq.redriven_from` set, the third record untouched, and a second run doubling the count. The stream side is covered by `StreamJobMiniClusterTest` (junk, `notes`, an unknown attribute and a redriven second failure leave the topology as schema-valid `stream-dlq.v1` records with the headers above). Compose-stack execution of the Makefile targets is still owed: record it here when it happens.
