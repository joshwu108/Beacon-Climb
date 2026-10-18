# test/integration/parity — how the parity oracle is used after chaos scenarios

BC-0306 ships the oracle (`make test-parity`, `scripts/parity/README.md` for the mechanics and the
report format). BC-0307's chaos scenarios (ADR-0004 "Failure cases to test": duplicate,
out-of-order revision, retraction, event at/after the lateness boundary, idle partition, Flink
restart after checkpoint, Kafka restart, materializer crash before/after commit, same-watermark
correction, checksum conflict, reconciliation) call it as their final assertion. Nothing in this
directory runs on its own; it documents the contract between the two tickets.

## Protocol per scenario

1. **Synthetic users only.** The scenario creates its users through the API with usernames
   starting with `synthetic_` (BC-0311's load generator does; `e2e_*` and `demo` are the other
   accepted prefixes). The oracle refuses to write a report (exit 2) when any compared user is a
   real account, so a scenario that reuses another account fails loudly.
2. **Inject the fault, then settle.** Parity is asserted only after the stream job has consumed
   the input through the cutoff `W` and reconciliation has completed (ADR-0004 "A recovered job
   may briefly emit an older full snapshot at a newer offset"). The settle step waits until the
   materializer's consumer lag on `climber-features.v1` is 0 and the STREAM row's `cutoff_w` of
   every scenario user is at least the `occurred_at` of the last admitted event, then (for the
   lateness and reconciliation scenarios) runs `make reconcile` and waits for its exit.
3. **Run the oracle** for the scenario's users and keep the report as evidence:

   ```sh
   make test-parity USER=all OUT=docs/evidence/BC-0307/<scenario>-parity.json
   # or one user at a time
   make test-parity USER=<uuid> OUT=docs/evidence/BC-0307/<scenario>-<n>-parity.json
   ```

   `USER=all` compares every user with a STREAM row, including users of earlier scenarios still
   in the database; that is intended (a fault must not corrupt a neighbour's row). Exit 0 is the
   scenario's pass; exit 1 fails it and the report's `details[].diff_keys` /
   `checksum_differs` say what diverged; exit 2 is a harness error (stack down, non-synthetic
   users, bad arguments) and never a parity verdict.
4. **Expected non-parity.** After the *reconciliation* scenario (an event more than 24 h behind
   the watermark) the STREAM row lacks the late attempt until a batch rebuild republishes a newer
   sequence (ADR-0004); `make reconcile` repairs the SYNC row only. BC-0307 records that run's
   exit 1 with the `diff_keys` and `checksum_differs: true` as the measured outcome, not as a
   pass. A catalog re-import between the write and the run moves the grade-derived keys without a
   checksum change (`climber-features.v1.md` §6): scenarios keep one catalog version.

## What a report proves

`equal == compared` at commit `commit` means: for every listed user, the materialized STREAM
snapshot at its `cutoff_w` equals the synchronous recompute from the canonical Postgres attempts
with `occurred_at <= cutoff_w` (highest revision per `attempt_id`, RETRACTED excluded), both
payload and provenance checksum, after the injected fault. It says nothing about freshness
(BC-0311 measures that) or about users without a STREAM row (`skipped_no_stream_row`).
