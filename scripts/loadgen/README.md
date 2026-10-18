# scripts/loadgen — synthetic event load and freshness measurement (BC-0311)

Everything this directory produces is **SYNTHETIC load; numbers describe the laptop they
were measured on; never a product claim**. A measured number may only be cited from a row in
`docs/evidence-ledger.md` (see `docs/benchmarks/README.md` for the procedure).

## What it does

`loadgen.py` drives the running compose api (`make dev && make seed-demo`, or
`make loadgen-stack` for a load run) over plain HTTP, standard library only:

1. registers `--users` accounts named `synthetic_<runid>_<i>` (`^[a-z0-9_]{3,32}$`; the
   username pattern forbids `-`), records consent against the **live** `GET /v1/consent/current`
   document (version + text sha256), sets preferences (`--board`, `--angle`);
2. fetches `GET /v1/routes` for that board/angle and posts attempts at `--rate` events/s
   (global, round-robin over the users; one attempt in flight per user) for `--duration`
   seconds: `occurred_at` = client UTC now at millisecond precision, `attempts_count` 1,
   `sent` random (`--seed`), one fresh `Idempotency-Key` per event;
3. polls `POST /v1/me/sessions/recommendations` for every user that has committed-but-unseen
   events every `--poll-interval` seconds (default 1 s) from a separate thread pool and marks
   an event **visible** the first time a response carries `feature_as_of >= occurred_at`;
   after `--visible-timeout` seconds (default 120 s) since commit it is **NOT_VISIBLE**.

Per event it records `occurred_at`, `sent_at`, `committed_at` (time of the 201 on the client
clock), `visible_at`, `features_source` / `feature_as_of` / `features_cutoff_w` of the first
visible response, the api's `received_at` (server clock, diagnostic offset), 429 counts and
retries. Every row carries `"synthetic": true`.

## Outputs (`--out`, default `reports/loadgen/<UTC timestamp>`, gitignored)

- `events.jsonl` — one row per event, all fields.
- `summary.json` / `summary.txt` — run id, parameters, hardware block (`sysctl`, `sw_vers`,
  `docker info`), allow-listed `BEACON_*` environment of the api container (so a report says
  which rate-limit overrides were active), counts (attempted, committed 201, failed by status,
  429s, visible, not visible), achieved committed rate, **freshness = visible_at − occurred_at**
  p50/p95/p99/max in ms (visible events only), commit latency p50/p95/p99, features_source
  distribution. Percentiles are nearest-rank (value at rank `ceil(p/100·n)`), never
  interpolated. `--evidence-copy <path>` writes `summary.txt` there as well.

`freshness.py <events.jsonl>` recomputes both summaries from a stored events file (keeping
the original run's environment block from the adjacent `summary.json`), so a report can be
regenerated and checked by someone else.

## Commands

```bash
python3 -I scripts/loadgen/loadgen.py --self-test          # percentile/summary/guard fixture, prints OK
python3 -I scripts/loadgen/loadgen.py --users 10 --rate 5 --duration 30 --out reports/loadgen/smoke
python3 -I scripts/loadgen/freshness.py reports/loadgen/smoke/events.jsonl
make loadgen USERS=20 RATE=10 DURATION=60 [OUT=reports/loadgen/x] [EVIDENCE=path]
make freshness EVENTS=reports/loadgen/x/events.jsonl
```

Exit code 0 when every event committed and became visible; 1 when any failed, timed out or
the run was interrupted (the outputs are still written).

## Guards

- The generator only ever authenticates as names it generated itself; `assert_synthetic_username`
  raises for anything not starting with `synthetic_` (asserted before every register/session call
  and in the `User` constructor). It never reads or touches `demo`, `admin` or any other account.
- `assert_allowed_request` refuses every method except GET/POST/PUT and every `/v1/admin*` path,
  so no delete, erasure or admin endpoint can be reached even by mistake.
- Tokens and the per-run random password are held in memory only; neither appears in logs or
  output files. Rows carry the synthetic username and attempt ids only.
- `--self-test` exercises both guards and the summary code without a network.

## Rate limits

With the api defaults one client gets 60 writes/min **per user** (attempts and recommendation
polls both count), register/session 30/min with burst 5 per client address and 20 registrations
per address per UTC day. A 429 is honoured with its `Retry-After` and counted; the smoke
parameters above stay inside the defaults, anything near the 100 events/s target does not.
See `docs/benchmarks/README.md` for `infra/compose/loadgen.yml` and `make loadgen-stack`.

## Caveats (read before quoting a number)

- All event timestamps come from the client clock on the same laptop; `received_at −
  committed_at` is reported as a server-clock-offset diagnostic. Freshness depends on the
  stream path only when `features_source` is STREAM; with the core profile it is SYNC and
  freshness is roughly commit latency plus one poll interval.
- Visibility is inferred from `feature_as_of` being the maximum `occurred_at` included; with one
  attempt in flight per user, per-user commit order equals `occurred_at` order, so an event with
  `occurred_at <= feature_as_of` was included. The stream path is at-least-once; a snapshot may
  lag or repeat, which shows up as a larger freshness, never as a false "visible".
- Every poll writes a decision record (`beacon.decisions.max-per-user-per-day`, default 500).
- Commit latency includes any Retry-After backoff a 429 caused; such events are counted.
