# Benchmarks: synthetic event load and feature freshness (BC-0311)

All traffic described here is **SYNTHETIC load; numbers describe the laptop they were measured
on; never a product claim**. A number may be quoted elsewhere only once it is a row in
[`docs/evidence-ledger.md`](../evidence-ledger.md) with its raw artifact under `docs/evidence/`.

## Recorded measurements

**No measurement recorded yet.** (The top-level session replaces this sentence with the ledger
pointer once the 100 events/s × 5 min run below has been made on the stream profile.)

## What is measured

`scripts/loadgen/loadgen.py` (stdlib Python, `python3 -I`) registers `synthetic_<runid>_<i>`
users, posts attempts at a global rate and polls `POST /v1/me/sessions/recommendations` per user
until the response's `feature_as_of` is at or after each event's `occurred_at`. Definitions:

| Quantity | Definition | Clock |
|---|---|---|
| `occurred_at` | attempt timestamp sent in the body; client UTC now (ms) when the POST starts | client |
| `committed_at` | time the 201 response was received | client |
| commit latency | `committed_at − sent_at` (includes any Retry-After backoff on a 429) | client |
| `visible_at` | time of the first recommendation response with `feature_as_of >= occurred_at` | client |
| **freshness** | `visible_at − occurred_at`, computed over VISIBLE events only; NOT_VISIBLE events (no such response within `--visible-timeout`, default 120 s) are counted separately | client |
| achieved rate | committed 201s ÷ (last commit − first send) | client |
| server clock offset | api `received_at` (server clock, at receipt) − `committed_at` (client, after the response): about −(server processing time) when the clocks agree; diagnostic only | both |

Percentiles are **nearest-rank** (the value at 1-based rank `ceil(p/100·n)` of the sorted sample):
every reported p50/p95/p99 is an observed value. Freshness is a stream-path number only when
`features_source` is `STREAM`; on the core profile it is `SYNC` (the synchronous post-commit
recompute), and freshness is then roughly commit latency plus the poll interval. A visible event
is inferred from `feature_as_of` being the maximum `occurred_at` the snapshot included and from
one attempt in flight per user (commit order equals `occurred_at` order); the stream path is
at-least-once, so a lagging or repeated snapshot shows as larger freshness, never as a false
visible.

## Rate limits bound one client

`services/api/src/main/resources/application.yaml` (ADR-0005, BC-0122/0123):

| Limit | Property | Default | Env override (relaxed binding) | `loadgen.yml` |
|---|---|---|---|---|
| writes per authenticated user per minute (attempts **and** recommendation polls) | `beacon.http.write-limit-per-minute` | 60, burst 60 | `BEACON_HTTP_WRITELIMITPERMINUTE` | 100000 |
| register/session per client address, refill | `beacon.http.public-write-limit-per-minute` | 30 | `BEACON_HTTP_PUBLICWRITELIMITPERMINUTE` | 100000 |
| register/session per client address, burst | `beacon.http.public-burst` | 5 | `BEACON_HTTP_PUBLICBURST` | 1000 |
| registrations per client address per UTC day | `beacon.auth.register-per-ip-per-day` | 20 | `BEACON_AUTH_REGISTERPERIPPERDAY` | 100000 |
| decision records per user per UTC day (one per poll) | `beacon.decisions.max-per-user-per-day` | 500 | `BEACON_DECISIONS_MAXPERUSERPERDAY` | 100000 |

The target run, 100 events/s over 50 users, is 2 events/s/user = 120 attempts/min/user plus up
to 60 polls/min/user, above the default 60/min, and 50 registrations exceed the 20/day cap, so
it **needs** the override: `infra/compose/loadgen.yml` (api environment only) merged last by
`make loadgen-stack`. The generator honours `Retry-After` on every 429 and counts them, so a run
inside the defaults is still honest, just slower; a `Retry-After` above `--max-retry-after`
(60 s, e.g. the until-midnight value of the daily register cap) aborts setup with a pointer here.
`summary.txt` prints the allow-listed `BEACON_*` environment of the api container
(`docker inspect compose-api-1`), so every report states which overrides were active;
a report with none listed ran on the application.yaml defaults.

## Commands

Smoke (core profile, default limits; features_source SYNC):

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
make dev && make seed-demo
python3 -I scripts/loadgen/loadgen.py --self-test
python3 -I scripts/loadgen/loadgen.py --users 10 --rate 5 --duration 30 --out reports/loadgen/smoke
make down
```

Target run for the ledger (stream profile with the overrides; BC-0305 materializer must be in
the api and the Flink job submitted, otherwise features_source stays SYNC):

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
make loadgen-stack            # core + stream profiles + infra/compose/loadgen.yml, --wait
make seed-demo                # board import; demo/admin users are not touched by the generator
make stream-submit            # the Flink feature job (make stream-job-status to confirm RUNNING)
make loadgen USERS=50 RATE=100 DURATION=300 OUT=reports/loadgen/target-100eps-5min
# or: python3 -I scripts/loadgen/loadgen.py --users 50 --rate 100 --duration 300 \
#       --out reports/loadgen/target-100eps-5min --evidence-copy docs/evidence/BC-0311/<name>.txt
make freshness EVENTS=reports/loadgen/target-100eps-5min/events.jsonl   # recompute to verify
make stream-down
```

Then: copy `summary.txt` (and, if small enough, `events.jsonl`) to `docs/evidence/BC-0311/`,
add one ledger row per claim (source commit, the exact command above, "synthetic users only,
synthetic-demo-1 fixture", the hardware block, the artifact path, a second reviewer), and
replace the "No measurement recorded yet" sentence above with the row pointer. `STREAM=0
make loadgen-stack` gives the core profile with the overrides when only the limits are needed.

## Hardware block template

`summary.txt` fills this from `sysctl -n machdep.cpu.brand_string`, `sysctl -n hw.memsize`,
`sw_vers` and `docker info`; the ledger `Environment` column should read like:

```
cpu: <machdep.cpu.brand_string>; memory_gib: <hw.memsize / 2^30>; os: <sw_vers product + version + build>;
docker: engine <ServerVersion>, <NCPU> cpus, <MemTotal> bytes for containers; python: <version>;
api environment: <BEACON_HTTP_*/BEACON_AUTH_*/BEACON_DECISIONS_*/BEACON_STREAM_ENABLED as printed, or "defaults">;
client: loadgen.py on the same laptop over loopback; profile: core | core+stream; Flink job: <RUNNING | none>
```

## Caveats

- Client and container clocks: every event timestamp is the client's; the server-clock-offset
  line in the summary shows how far the api's `received_at` sits from the client's commit time.
- Rate accounting: the achieved rate is committed events over the production window; if the
  api or the per-user in-flight rule slows the producer the achieved rate drops and is reported,
  the target is never asserted.
- Loopback only: `--base-url` defaults to `http://127.0.0.1:8080` (the api directly, not nginx
  on :3000), so the per-address limits see one address for the whole run.
- Each poll writes a decision record and a candidate snapshot file (`beacon.decisions.max-files`,
  100000); a 5-minute run with 50 users polling at 1 s writes at most 15000.
- `make dev` or `make stream-up` after `make loadgen-stack` recreates the api without the
  overrides.
