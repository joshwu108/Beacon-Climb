# climber-features.v1 — canonical per-user feature recompute

**Status:** NORMATIVE for BC-0113 (synchronous SQL recompute) and the parity oracle of ADR-0004.
**Schema version:** `feature_schema_version = 1`. **Owner of the storage row:** `user_feature_snapshots`
(`services/api/src/main/resources/db/migration/V4__feature_snapshots.sql`). **Implementation:**
`com.beaconclimb.api.features` (`FeatureRecomputeService`, `FeatureComputer`, `FeatureAggregates`).

Source of the aggregate list: `planning/06-first-two-sprints.md` §8. This document fixes every edge
that §8 leaves implicit so that the streaming job (BC-0304) and the batch recompute produce
byte-identical `aggregates` and identical `provenance_checksum` at an equal cutoff `W`.

## 1. Inputs

The input set `A(u, W)` for user `u` and explicit cutoff `W` is every row of `attempts` with

* `user_id = u`,
* `status = 'ACTIVE'` (RETRACTED attempts contribute nothing, to no aggregate and not to the checksum),
* `occurred_at <= W` (inclusive).

"Latest revision per `attempt_id`" is the `attempts` row itself: `attempts.revision` is the current
revision and `attempt_revisions` is an audit copy only. A stream consumer that keeps the highest
revision per `attempt_id` (with a tombstone for `ATTEMPT_RETRACTED`) holds exactly `A(u, W)`.

Each included row contributes `(attempt_id, revision, route_variant_public_id, occurred_at, sent)`
plus the variant's **current** `route_variants.grade_numeric` (nullable) joined through
`attempts.variant_id`. `attempts_count`, `completion_fraction`, `perceived_effort` and `notes` are
not inputs in v1.

`occurred_at` is stored at millisecond precision (ingest truncation, BC-0109); every comparison below
is on that stored value.

## 2. Windows and ordering

* A window of `N` days is the half-open interval `(W − N·24h, W]`: `occurred_at > W − N·24h` and
  `occurred_at <= W`. Days are exactly 24 h; no calendar or time-zone arithmetic.
* Canonical order of included rows: `occurred_at DESC, attempt_id DESC` ("newest first").
  `attempt_id` is a UUIDv7 compared as a 128-bit value (SQL `uuid` ordering, `UUID.compareTo` on
  the Java side gives the same order for version-7 ids with the variant bits set).
* The result depends only on the set `A(u, W)` and `W`; arrival order, duplicates and superseded
  revisions never affect it (the de-duplicated set is the definition).

## 3. Aggregates (`aggregates_json`)

Exactly these eleven keys, no others. Types are JSON types as stored.

| key | type | definition |
|---|---|---|
| `attempts_7d` | integer ≥ 0 | number of included **attempt records** with `occurred_at` in `(W − 7d, W]`. (`attempts_count`, the self-reported tries inside one record, is not summed in v1.) |
| `attempts_30d` | integer ≥ 0 | same over `(W − 30d, W]` |
| `attempts_90d` | integer ≥ 0 | same over `(W − 90d, W]` |
| `sends_7d` | integer ≥ 0 | included records with `sent = true` in `(W − 7d, W]` |
| `sends_30d` | integer ≥ 0 | same over `(W − 30d, W]` |
| `sends_90d` | integer ≥ 0 | same over `(W − 90d, W]` |
| `send_rate_by_grade_band_90d` | object | keyed by the grade band of §4 for every band that has at least one record in `(W − 90d, W]` (sparse: absent bands are omitted, `{}` when the window is empty). Each value is `{"attempts": <records>, "sends": <records with sent = true>}`. No ratio is stored; consumers divide. |
| `recent_variant_ids` | array of string, ≤ 20 | the first 20 **distinct** `route_variant_public_id` values in canonical order (newest first, ties by `attempt_id DESC`) over the whole of `A(u, W)` (no window). |
| `last_session_at` | RFC 3339 string or null | `max(occurred_at)` over `A(u, W)`; null when the set is empty. By construction equal to `feature_as_of` in v1. |
| `max_sent_grade_numeric_90d` | number or null | `max(grade_numeric)` over records in `(W − 90d, W]` with `sent = true` and a non-null `grade_numeric`; null when there is no graded send. |
| `distinct_sessions_30d` | integer ≥ 0 | number of sessions among records in `(W − 30d, W]` (§5). |

Timestamps are RFC 3339 UTC with a `Z` designator, fractional seconds omitted when zero and otherwise
the shortest group of 3 digits that round-trips (`java.time.format.DateTimeFormatter.ISO_INSTANT`;
inputs are millisecond precision so at most three fraction digits appear): `2026-10-14T14:00:00Z`,
`2026-10-14T14:00:00.250Z`.

Numbers follow RFC 8785 (ECMAScript `Number::toString`): `20`, not `20.0`; `20.5`.

### Canonical bytes

`aggregates_json` is written as the RFC 8785 (JCS) canonical text of the object above
(`FeatureAggregates.toJson()`): members sorted by UTF-16 code units, no whitespace. Two recomputes
of the same `A(u, W)` therefore produce identical bytes. Postgres `jsonb` re-normalises on storage;
compare on the canonical text, not on `jsonb::text`.

## 4. Grade bands

Band of a record = band of the variant's current `grade_numeric` through this fixed table
(`GradeBandBuckets`). The edges are planning placeholders until the grade scale contract
(`contracts/catalog/grade-scale.v1.json`) lands; they claim nothing about physical difficulty.

| key | `grade_numeric` |
|---|---|
| `NOVICE` | `< 14` |
| `BEGINNER` | `>= 14` and `< 18` |
| `INTERMEDIATE` | `>= 18` and `< 22` |
| `ADVANCED` | `>= 22` and `< 26` |
| `EXPERT` | `>= 26` |
| `UNGRADED` | `grade_numeric IS NULL` |

The five named keys are the `GradeBand` enum of `contracts/openapi.yaml`; `UNGRADED` is an extra
key of this map only.

## 5. Sessions

Take the records of the 30-day window in **ascending** canonical order (`occurred_at ASC,
attempt_id ASC`). The first record opens session 1; each record whose `occurred_at` is **strictly
more than 3 hours** (`> 10 800 000 ms`) after the previous record's `occurred_at` opens a new session.
Records exactly 3 h apart stay in one session. Example: records at `t`, `t+1h`, `t+2h59m`, `t+6h`
give `distinct_sessions_30d = 2`. Sessions are computed from the windowed records only; a session
that straddles the window edge is counted from its in-window records.

## 6. Provenance and timestamps of the snapshot row

| column | definition |
|---|---|
| `cutoff_w` | the explicit `W` the recompute was asked for (`now` at the attempts hook and the CLI default). |
| `feature_as_of` | `max(occurred_at)` over `A(u, W)`, null when empty. Always `<= cutoff_w`. The API (BC-0111) exposes this; parity keys on `cutoff_w`. |
| `provenance_checksum` | `sha256(UTF-8(join(",", sorted(["<attempt_id>:<revision>" for each included record]))))` as 64 lowercase hex characters. `attempt_id` is the canonical lowercase 36-character UUID text, `revision` the decimal integer; the strings are sorted with plain code-unit ordering (`String.compareTo`). An empty set hashes the empty string: `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`. |
| `feature_schema_version` | `1`. |
| `source` | `SYNC` for this recompute, `STREAM` for the BC-0305 materializer. Since V9 (`V9__feature_snapshots_stream.sql`) the two coexist as separate rows under the primary key `(user_id, feature_schema_version, source)`: the SYNC row keeps the monotonic `cutoff_w` rule (BC-0124 HIGH-2); the STREAM row is guarded only by its `(source_partition, source_offset)` (ADR-0004: apply when the offset is greater on the same partition, equal offset + same checksum is a duplicate, equal offset + different checksum goes to the DLQ, a different partition is a conflict, never a watermark-only guard); the read path (BC-0310 part 2) prefers STREAM when its `cutoff_w` is at least the SYNC `cutoff_w`. |
| `source_partition`, `source_offset` | null for `SYNC`; the consumed Kafka position for `STREAM`. |
| `computed_at` | wall-clock time of the write (not an input to anything). |

The checksum covers the attempt provenance only. A catalog re-import that changes a variant's
`grade_numeric` changes `send_rate_by_grade_band_90d` / `max_sent_grade_numeric_90d` without changing
the checksum; parity runs must use one catalog version (ADR-0004 reconciliation).

## 7. When the recompute runs

* After every successful attempt create, correction or retraction, in the same request but **after**
  that transaction committed, in its own transaction, with `W = now` (millisecond-truncated
  application clock). A failure is logged at WARN with the user id and never fails the attempt write
  (`FeatureRecomputeHook`). An attempt whose `occurred_at` is in the allowed five-minute future skew
  is excluded until a later recompute reaches it.
* `make recompute-features [USER=<uuid>|all] [W=<RFC 3339 instant>]` (`--spring.profiles.active=recompute`)
  for one user or every account with `status <> 'DELETED'`, printing
  `recomputed user_id=<id> cutoff_w=<W> feature_as_of=<instant|null> checksum=<hex>` per user.
* No endpoint reads the row in BC-0113; BC-0111 exposes `feature_as_of`.

## 8. Relation to other contracts

* **`contracts/events/climber-features.v1.schema.json`** (`aggregates` member): same eleven keys,
  same types, `additionalProperties: false`. The stream job must emit the `aggregates` object exactly
  as §3 and `provenance_checksum` exactly as §6; `source_watermark` corresponds to `cutoff_w`. No key
  mapping is needed.
* **`contracts/decisions/decision-record.v1.schema.json`** (`$defs/userFeatures`): the frozen
  `user_features` of a decision is this object plus `target_grade_numeric`, which the ranking policy
  adds at request time (BC-0110/BC-0111). All eleven keys validate against that definition.
* **ADR-0004:** this recompute is the batch side of the parity invariant; BC-0306 compares
  `aggregates_json` bytes and `provenance_checksum` at equal `cutoff_w`.

## 9. Change control

Any change to a definition above, to the key set, to the band edges, to the timestamp or number
format, or to the checksum recipe is a new `feature_schema_version` and a new document version;
rows of different versions coexist under the composite primary key.

## 10. Stream implementation notes (BC-0304)

The Flink job (`services/stream`, `com.beaconclimb.stream.features.ClimberFeaturesFunction`) and
the api recompute share one implementation of §1–§6: the plain-Java module `services/features`
(`com.beaconclimb.features.FeatureComputer`, `FeatureAggregates`, `GradeBandBuckets`,
`AttemptFeatureRow`), originally a verbatim port of the api classes. Since commit 998e274
(BC-0306) the api depends on the module and its own copies are deleted; the committed golden
fixtures (`services/features/src/test/resources/golden/climber-features-v1`, format in
`services/features/README.md`) are what both consumers are held to.

* **State.** Keyed by `user_id`; `MapState<attempt_id, AttemptState(revision, tombstone,
  occurred_at, route_variant_id, sent, grade_numeric)>` holds the highest revision seen per attempt.
  `ATTEMPT_RECORDED` / `ATTEMPT_CORRECTED` with a revision above the stored one replaces the entry;
  `ATTEMPT_RETRACTED` with a higher revision tombstones it (the revision stays, the row contributes
  nothing); a revision lower than or equal to the stored one, including a duplicate delivery,
  changes nothing and emits nothing. The non-tombstoned entries are exactly `A(u, W)` of §1 before
  the cutoff filter.
* **Cutoff `W` (the parity rule).** On every applied change `W := max(current watermark,
  occurred_at of the event just applied)` and the aggregates are recomputed over the entries with
  `occurred_at <= W`; `source_watermark = W`. Entries ahead of `W` (out-of-orderness allows
  `occurred_at > watermark`) stay in state and enter a later recompute once `W` reaches them, so a
  record always equals the batch recompute at `cutoff_w = source_watermark` and
  `feature_as_of <= source_watermark` holds. Before any watermark exists (job start, and the first
  event after a restore, because Flink does not checkpoint watermarks) `W = occurred_at`.
* **Empty included set.** When nothing is included (everything retracted, or every entry ahead of
  `W`) the record carries the empty-set aggregates (`last_session_at: null`, the checksum of the
  empty string) and, since the event schema requires a timestamp, `feature_as_of = source_watermark`.
  The batch side stores `feature_as_of = null` for the same set; parity compares `aggregates` and
  `provenance_checksum` only.
* **Late events.** An event with `occurred_at < current watermark − 24 h` (the allowed lateness) is
  not applied: its original bytes go to `stream-reconciliation.v1` keyed by `user_id` with the
  headers `reason=LATE_BEYOND_WINDOW` and `watermark=<W>` (`W` as above, which for a late event is
  the current watermark). Events up to 24 h behind the watermark are applied with `W` = the
  watermark. With the production watermark trailing the newest `occurred_at` by 24 h this admits
  events up to 48 h behind the newest event seen; the exact boundary is tested
  (`ClimberFeaturesFunctionTest.latenessBoundary`). Invalid bytes never reach this operator (DLQ,
  BC-0302); an event whose `user_id` key is empty is ignored.
* **`grade_numeric` comes from the event.** The stream reads the optional
  `attributes.grade_numeric` (the variant's grade when the attempt was written; absent or `null` is
  UNGRADED) and never joins the catalog. The batch side joins the variant's **current**
  `grade_numeric` (§1). The two agree while the catalog is unchanged between the write and the
  recompute; a catalog re-import that changes a variant's grade moves
  `send_rate_by_grade_band_90d` / `max_sent_grade_numeric_90d` on the batch side only (the checksum
  is unaffected either way, §6), so parity runs use one catalog version and reconciliation
  (ADR-0004) republishes after a re-import.
* **Record bytes.** `aggregates` is embedded as the canonical text of `FeatureAggregates.toJson()`
  unchanged (a raw value inside the envelope, not re-serialised); `produced_at` is the operator's
  processing time; `producer = "stream"`; `event_id` is a UUIDv7.
