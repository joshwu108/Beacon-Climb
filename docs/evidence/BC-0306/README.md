# BC-0306 — batch parity oracle evidence

Reports written by `make test-parity [USER=<uuid>|all] [OUT=<path>]` land here as
`parity-<yyyyMMddTHHmmssZ>.json` (the default `OUT`). The mechanics, verdicts, exit codes and the
report format are in `scripts/parity/README.md`; how BC-0307 runs the oracle after each chaos
scenario is in `test/integration/parity/README.md`.

No measured report is committed by the implementation handoff: the oracle was proven against
Testcontainers Postgres (`ParityServiceIT`, `ParityRunnerTest`, `ParityProfileIT`,
`GoldenFixturesApiTest`) and the first run against the live compose stack (Kafka, Flink job,
materializer, synthetic traffic from BC-0311's load generator) is recorded by the integration
session that runs it, with its `commit`, the one-line `parity summary ...` output and the file.

## Reading a report

```text
parity summary users=<n> with_stream_row=<n> compared=<n> equal=<n> diffs=<n> same_w_mismatch=<n> skipped_no_stream_row=<n> synthetic_only=true out=<path>
```

* `equal == compared` and exit 0: the ADR-0004 invariant held for every user with a STREAM row at
  that row's `cutoff_w` (payload and checksum), at the recorded `commit`.
* `diffs > 0`: open `details[]` with `status: DIFF`; `diff_keys` names the aggregate keys that
  differ and `checksum_differs` says whether the included row set differs (checksum) or only the
  values (a catalog re-import moves the grade-derived keys without a checksum change,
  `climber-features.v1.md` §6).
* `same_w_mismatch > 0`: the SYNC and STREAM rows of a user share `cutoff_w` but not the
  checksum; the batch comparison in the same detail says which side disagrees with Postgres now.
* `skipped_no_stream_row`: users the stream has not materialized; not a verdict.
* The summary carries counts only; `details[].user_id` are ids of `synthetic_*` / `e2e_*` / `demo`
  accounts (the runner refuses to write anything else, exit 2).

## CI decision

`.github/workflows/ci.yml` is unchanged. The oracle's logic runs in CI already through
`./gradlew :api:test` (`ParityServiceIT`, `ParityRunnerTest`, `ParityProfileIT` and the
`GoldenFixturesApiTest` replay of the 16 shared golden fixtures through the SQL path), so a
regression in the comparison, the read-only guarantee or the CLI contract fails the `api` job.
The live `make test-parity` is not run in CI: a meaningful run needs the stream profile (Kafka
4.3.1 KRaft, the Flink session cluster and the submitted feature job, the materializer under
`BEACON_STREAM_ENABLED=true`) plus synthetic traffic, which the `compose-smoke` job (core profile,
20-minute budget) does not start, and a run with no STREAM row is exit 0 with `compared=0`, which
would be a vacuous pass. BC-0307 (chaos scenarios) owns the live runs and their evidence; if a
stream-profile CI job is added later it can call `make test-parity` after a settle step without
any new action (the SHA-pinned set stays as is).
