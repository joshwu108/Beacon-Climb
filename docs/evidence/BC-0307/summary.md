# BC-0307 — fault scenario suite: measured runs

Every row below was produced by `make test-chaos` (test/chaos/stream/run.sh) against the compose
stream stack on this laptop; nothing here is typed by hand. Per-scenario transcripts are the
`<scenario>.txt` files next to this one, parity reports `parity-<scenario>.json` (the oracle of
BC-0306, scripts/parity/README.md). Scenario semantics and what each assertion proves:
test/chaos/README.md. All users are `synthetic_*` accounts created by the run.

## Run 20261018T083030Z (commit 727bf17, PARITY=skip, CHAOS_INVOKE=direct, scenarios: same-watermark-correction)

Hardware:

- cpu: Apple M5
- memory_gib: 24.0
- os: macOS 27.0 26A428
- docker: engine 29.2.1, 10 cpus, 8217059328 bytes for containers
- python: 3.14.2
- client: this script on the same laptop as the compose stack (loopback); client clock for every timestamp

| scenario | result | runtime s | converge s | parity | attempts expected/found | dlq growth | notes |
|---|---|---|---|---|---|---|---|
| same-watermark-correction | FAIL | 91 | - | skipped | ?/? | ?/? | scenario script scripts/chaos/same-watermark-correction.sh exited non-zero |
| all-users parity | skipped | - | - | skipped (PARITY=skip) | - | - | mechanics only |

Run 20261018T083030Z finished 2026-10-18T08:32:05Z: 95 s wall-clock, 1 failure(s). docker stats: docker-stats.txt.

## Run 20261018T083300Z (commit 254b918, PARITY=run, CHAOS_INVOKE=make, scenarios: same-watermark-correction)

Hardware:

- cpu: Apple M5
- memory_gib: 24.0
- os: macOS 27.0 26A428
- docker: engine 29.2.1, 10 cpus, 8217059328 bytes for containers
- python: 3.14.2
- client: this script on the same laptop as the compose stack (loopback); client clock for every timestamp

| scenario | result | runtime s | converge s | parity | attempts expected/found | dlq growth | notes |
|---|---|---|---|---|---|---|---|
| same-watermark-correction | PASS | 51 | 11 | EQUAL (exit 0) | 1/1 | 0/0 |  |
| all-users parity | PASS | - | - | exit 0 | - | - | users=4 with_stream_row=2 compared=2 equal=2 diffs=0 same_w_mismatch=0 |

Run 20261018T083300Z finished 2026-10-18T08:33:58Z: 58 s wall-clock, 0 failure(s). docker stats: docker-stats.txt.

## Run 20261018T083513Z (commit 254b918, PARITY=run, CHAOS_INVOKE=make, scenarios: dup-publish late-2h-inside-window late-3d-reconciliation correction-then-retraction api-sigkill-during-burst api-sigkill-after-publish flink-taskmanager-restart kafka-restart same-watermark-correction invalid-events-to-dlq)

Hardware:

- cpu: Apple M5
- memory_gib: 24.0
- os: macOS 27.0 26A428
- docker: engine 29.2.1, 10 cpus, 8217059328 bytes for containers
- python: 3.14.2
- client: this script on the same laptop as the compose stack (loopback); client clock for every timestamp

| scenario | result | runtime s | converge s | parity | attempts expected/found | dlq growth | notes |
|---|---|---|---|---|---|---|---|
| dup-publish | PASS | 52 | 3 | EQUAL (exit 0) | 5/5 | 0/0 |  |
| late-2h-inside-window | PASS | 22 | 11 | EQUAL (exit 0) | 5/5 | 0/0 |  |
| late-3d-reconciliation | FAIL | 9 | - | skipped | ?/? | ?/? | scenario script scripts/chaos/late-3d-reconciliation.sh exited non-zero |
| correction-then-retraction | PASS | 15 | 2 | EQUAL (exit 0) | 0/0 | 0/0 |  |
| api-sigkill-during-burst | PASS | 27 | 3 | EQUAL (exit 0) | 50/50 | 0/0 |  |
| api-sigkill-after-publish | PASS | 35 | 7 | EQUAL (exit 0) | 50/50 | 0/0 |  |
| flink-taskmanager-restart | PASS | 37 | 12 | EQUAL (exit 0) | 15/15 | 0/0 |  |
| kafka-restart | PASS | 28 | 11 | EQUAL (exit 0) | 10/10 | 0/0 |  |
| same-watermark-correction | PASS | 20 | 11 | EQUAL (exit 0) | 1/1 | 0/0 |  |
| invalid-events-to-dlq | PASS | 36 | 8 | EQUAL (exit 0) | 1/1 | 2/2 |  |
| all-users parity | FAIL | - | - | exit 2 | - | - | users=14 with_stream_row=12 compared=12 equal=11 diffs=1 same_w_mismatch=0 |

Run 20261018T083513Z finished 2026-10-18T08:40:09Z: 296 s wall-clock, 2 failure(s). docker stats: docker-stats.txt.
