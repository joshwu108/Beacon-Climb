# scripts/parity — batch parity oracle (BC-0306)

`parity.sh` wraps the api jar's `parity` Spring profile (`com.beaconclimb.api.parity`): the
testable invariant of ADR-0004 as a CLI. Run it through `make test-parity [USER=<uuid>|all]
[OUT=<path>]` against a migrated compose stack (`make dev` or `make stream-up`); the script reads
only `BEACON_APP_DB_PASSWORD` from `.env` and connects as the runtime role to
`127.0.0.1:5432` (override with `BEACON_DB_URL`). No web server, no Kafka, no Flyway.

## What it checks

For every non-erased user (or the one given) that has a `STREAM` row in `user_feature_snapshots`:

1. `W := stream.cutoff_w` (the Flink `source_watermark` the materializer stored).
2. batch := `FeatureRecomputeService.compute(user, W)`: the SYNC recompute's query (current
   revision of every ACTIVE attempt with `occurred_at <= W`, joined to the variant's current
   `grade_numeric`) folded through the shared `com.beaconclimb.features.FeatureComputer`. Nothing
   is written: the SYNC row is left alone.
3. Compare `FeatureAggregates.toJson()` of the batch result with `Jcs.canonicalize(stream
   aggregates_json::text)` (jsonb re-normalises whitespace and key order on storage, so the stored
   text is canonicalised first, never re-serialised through a typed object) and the two
   `provenance_checksum` values.
4. Cross-check: when a SYNC row exists with the same `cutoff_w` as the STREAM row, their checksums
   must match.

`feature_as_of` is reported for both rows but not compared: an empty-set STREAM record carries
`source_watermark` there while the batch stores null (`climber-features.v1.md` §10). Grade-derived
keys (`send_rate_by_grade_band_90d`, `max_sent_grade_numeric_90d`) can differ without a checksum
difference after a catalog re-import (§6); the `diff_keys` make that case visible.

## Verdicts and exit codes

| status | meaning | counted in |
|---|---|---|
| `EQUAL` | aggregates text and checksum equal | `equal` |
| `DIFF` | any top-level aggregate key differs, or the checksum differs | `diffs` |
| `SAME_W_MISMATCH` | SYNC and STREAM rows share `cutoff_w` but not the checksum (takes precedence over EQUAL / DIFF; the batch comparison is still filled in) | `same_w_mismatch` |
| `NO_STREAM_ROW` | the user has no STREAM row; skipped | `skipped_no_stream_row` |

`compared = equal + diffs + same_w_mismatch = users_with_stream_row`.

Exit 0 iff `diffs == 0 && same_w_mismatch == 0` (a run with no STREAM row at all is 0 with
`compared=0`); 1 otherwise; 2 for a bad argument, a failed run, or the **synthetic-only refusal**:
if any compared user is not a `synthetic_*`, `e2e_*` or `demo` account the report is not written
and the count of such users is printed to stderr. Only counts are ever printed; the report is the
only place user ids appear, and it lives under `docs/evidence/BC-0306/` by default, which is why
real accounts are refused.

## Report format

Written pretty-printed to `OUT` (default `docs/evidence/BC-0306/parity-<yyyyMMddTHHmmssZ>.json`):

```json
{
  "generated_at": "2026-10-18T10:00:00.000Z",
  "commit": "<git rev-parse HEAD, BEACON_GIT_COMMIT, or \"unknown\">",
  "users_total": 12,
  "users_with_stream_row": 10,
  "compared": 10,
  "equal": 10,
  "diffs": 0,
  "same_w_mismatch": 0,
  "skipped_no_stream_row": 2,
  "synthetic_only": true,
  "details": [
    {
      "user_id": "018f0000-0000-7000-8000-000000000001",
      "status": "EQUAL",
      "cutoff_w": "2026-10-18T09:59:58.000Z",
      "sync_cutoff_w": "2026-10-18T09:59:58.000Z",
      "stream_feature_as_of": "2026-10-18T09:59:58.000Z",
      "batch_feature_as_of": "2026-10-18T09:59:58.000Z",
      "stream_partition": 3,
      "stream_offset": 41,
      "diff_keys": [],
      "checksum_differs": false
    }
  ]
}
```

* `user_id` only, never a username; the summary members are counts.
* `cutoff_w` is the STREAM row's W (the batch ran at it); `sync_cutoff_w` is null without a SYNC
  row. `stream_feature_as_of` / `batch_feature_as_of` are the two rows' `feature_as_of`
  (`batch_feature_as_of` null for an empty included set).
* `stream_partition` / `stream_offset` are the materializer's stored Kafka position.
* `diff_keys` lists the top-level keys of `aggregates` whose canonical values differ (empty for
  EQUAL); `checksum_differs` says whether `provenance_checksum` differs.
* A `NO_STREAM_ROW` detail has nulls, an empty `diff_keys` and `checksum_differs: false`.

## Guarantees and tests

* Read-only: `ParityServiceIT.oracleNeverWrites` snapshots row counts and the latest
  `computed_at` / `updated_at` of the tables the oracle touches before and after a run.
* `ParityRunnerTest`: exit codes, the refusal (no file written), the summary line, the default
  output path, without a database.
* `ParityProfileIT`: the `parity` profile boots headless without Kafka or Flyway, runs once over a
  seeded synthetic user and exits 0 with the report on disk.
* Timing (ADR-0004): parity holds only after the stream has consumed the input through `W` and
  reconciliation has completed; run the oracle after the chaos scenario's settle step
  (test/integration/parity/README.md), not during it.
