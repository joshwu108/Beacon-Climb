# ADR-0004 — Stream semantics and engine

**Status:** PROPOSED · **Task:** BC-0012 · **Date:** 2026-10-14

## Testable invariant

For every user `u` and explicit cutoff `W`, after processing all events admitted through `W` and completing reconciliation for events outside the 24-hour lateness window, `materialized_snapshot(u,W).aggregates` and `provenance_checksum` equal a batch recomputation from Postgres canonical attempts with `occurred_at <= W`, selecting the highest revision for each `attempt_id` and excluding `RETRACTED`; duplicate delivery, reordering, restart and equal-watermark corrections cannot change the result. BC-0306 compares both payload and checksum after every BC-0307 scenario. `W` is stored separately from `feature_as_of=max occurred_at considered`.

## Processing rules

Kafka delivery and the Flink-to-Kafka-to-Postgres path are at-least-once. Flink state is keyed by `user_id`; MapState holds the highest revision by `attempt_id`, including a tombstone for retraction. Duplicate or lower revision has no effect. A higher revision replaces the old contribution and recomputes aggregates from canonical MapState. Event time uses `occurred_at`, bounded out-of-orderness 24 h and idle-partition handling. Checkpoints every 10 s to a local filesystem in Compose; checkpointing alone does not make arbitrary external writes transactional.

Flink emits `climber-features.v1` with `source_watermark` and `provenance_checksum`. The API materializer reads the Kafka feature-topic partition and offset from consumer metadata and transactionally upserts `(user_id, feature_schema_version)` only if the incoming offset is greater than the stored offset for that partition. Equal offset and different checksum goes to DLQ; equal offset and same checksum is a duplicate. All records for a user use the same key and partition; changing partition count requires a controlled reset/rebuild. Kafka offsets advance for a correction at an unchanged watermark and remain monotonic across Flink checkpoint restarts. A manual Kafka ack follows commit. A recovered job may briefly emit an older full snapshot at a newer offset; parity is asserted only after recovery has consumed the input through cutoff W and reconciliation completes. The synchronous SQL batch recompute is the parity oracle (`contracts/features/climber-features.v1.md` when BC-0113 lands). Events beyond 24 h enter reconciliation; a batch rebuild publishes a newer sequence. No Redis is in the read path.

## Failure cases to test

BC-0307: duplicate, out-of-order revision, retraction, event at/after lateness boundary, idle partition, Flink restart after checkpoint, Kafka restart, materializer crash before/after commit, same-watermark correction, checksum conflict, reconciliation. BC-0306 runs the invariant after each. DLQ redrive must preserve original event bytes and increment attempt count.

## Compatibility check (official sources, checked 2026-10-14)

[Apache Flink downloads](https://flink.apache.org/downloads/) lists Flink 2.3.0, but `flink-connector-kafka` 4.0.1 supports only Flink 2.0.x; 5.0.0 supports 2.1.x and 2.2.x. Thus the proposed 2.3.x/4.0.x pair is **incompatible**. Use Flink **2.2.1** plus connector **5.0.0** for implementation. [Apache Kafka downloads](https://kafka.apache.org/community/downloads/) lists `apache/kafka:4.3.1`; Kafka 4 runs KRaft. Flink's matrix does not state broker compatibility with Kafka 4.3.1, so BC-0303 must start a Kafka 4.3.1 KRaft broker and prove source and sink round trips, restart and offset recovery. No interoperability claim before that test.

Switch to Kafka Streams if the connector fails that test, the Flink job and rest of Compose exceed 4 GB RAM on the documented laptop, or MiniCluster tests exceed 10 minutes in CI. Preserve topic contracts, materializer and parity oracle. BC-0312 records the measured choice.
