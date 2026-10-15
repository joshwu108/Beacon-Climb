# Runbook: local dev stack (`make dev`, `make seed-demo`)

Ticket BC-0120. One command brings up Postgres, the API and the web client on the
documented laptop; a second one loads the synthetic board and the demo accounts. Files:
`infra/compose/docker-compose.yml`, `apps/web/Dockerfile` + `apps/web/nginx/`,
`scripts/seed-demo.sh`, the root `Makefile`, CI job `compose-smoke` in
`.github/workflows/ci.yml`.

## Prerequisites

- Docker Desktop (or any Docker Engine with Compose v2) running.
- JDK 21 on `PATH` or in `JAVA_HOME` (`mise.toml` pins 21.0.12.1). It builds the API jar
  and runs the catalog importer on the host; the api container itself uses the
  `eclipse-temurin:21-jre` image.
- `curl` and `python3` (the seed script uses both; macOS and ubuntu-latest have them).
- No local pnpm/node needed: the web image builds inside Docker.

## Run

```sh
cp .env.example .env            # then set every value, e.g. openssl rand -hex 24 per line
make dev                        # jar + web image + postgres/api/web, waits for healthchecks
make seed-demo                  # synthetic board, demo user, admin user; prints the URLs
open http://127.0.0.1:3000      # sign in as demo / demo-password
```

`make dev` refuses to start while any `.env` value is missing or empty (`${VAR:?}` in
compose, TM-27). Measured on the reference laptop (Apple Silicon, Docker Desktop) with
warm Gradle and BuildKit caches: `make dev` 15 s from empty volumes, `make seed-demo`
3 s; `docker build --no-cache` of the web image 8 s with the pnpm store cache mount
still populated. The very first build additionally pulls `node:22.23.3-alpine`,
`nginxinc/nginx-unprivileged:1.27-alpine`, `alpine:3.21` and fills the pnpm store, so it
is bounded by your network, not the CPU.

### What `make dev` starts (profile `core`)

| Service | Image | Host port (127.0.0.1 only) | Notes |
|---|---|---|---|
| `postgres` | `postgres:17` | 5432 | roles from `postgres/init.sql`; data in volume `pgdata` |
| `decisions-init` | `alpine:3.21` | – | one-shot `chown` of volume `decisions` to uid 65534, then exits |
| `api` | `eclipse-temurin:21-jre` | 8080 | runs as `nobody`, read-only root, only the jar (ro) and `decisions` (rw at `/data/decisions`, `BEACON_DECISIONS_DIR`) mounted |
| `web` | `beacon-web:dev` (built from `apps/web/Dockerfile`) | 3000 | nginx 1.27 unprivileged (uid 101), read-only root + tmpfs `/tmp`, serves `dist/` and proxies `/v1/` and `/health/` to `api:8080` |

Every container has `no-new-privileges`, `cap_drop: ALL` (the init container adds only
`CHOWN`), memory/CPU caps and json-file log rotation.

### How the browser reaches the API

The web image is built with `VITE_API_BASE_URL=http://127.0.0.1:3000`, i.e. its own
origin, and nginx forwards `/v1/*` and `/health/*` to the api container. The browser
therefore never makes a cross-origin request and no CORS exception is needed. Open the app
as `http://127.0.0.1:3000`, not `localhost:3000`: the page's CSP (`connect-src 'self'`) and the
baked-in base URL point at 127.0.0.1, so a `localhost:3000` page cannot reach the API
(BC-0123 I2). The api still lists both 3000 origins and the Vite dev-server origins on 5173 in
`BEACON_HTTP_CORSALLOWEDORIGINS`; any other origin gets 403 from the API's CORS filter.
`X-Request-Id` passes through nginx unchanged (the API echoes it).

`beacon.http.trusted-proxies` is empty by default (BC-0123 F3), so the API keys every
per-IP limit on the socket peer and ignores `X-Forwarded-For`. nginx sets that header
from `$remote_addr` (overwrite, never append), so it can be trusted once the proxy is
listed, but compose sets nothing because Docker bridge addresses vary between hosts.
Consequences behind nginx on :3000:

- the public POST bucket (`beacon.http.public-burst` 5, refilling at
  `beacon.http.public-write-limit-per-minute` 30) is shared by every browser user; and
- the **login lockout is per IP too** (ADR-0005: 10 failed logins per 15 minutes per
  username *and* per client IP), so 10 failed logins by anyone through :3000 lock every
  browser user out of sign-in for 15 minutes. Direct calls to :8080 keep their own key.

Fine for one laptop; before any shared host set
`BEACON_HTTP_TRUSTEDPROXIES=<web container IP or subnet>` on the `api` service (a list of IP
literals or CIDR ranges, e.g. the compose network's subnet from
`docker network inspect <project>_default`). Then the first `X-Forwarded-For` entry is the
client for requests whose peer is in that list, and :8080 callers (whose peer is the Docker
gateway, not the web container) stay keyed on their own address. Never list a range that a
client can connect from directly, and never put an appending proxy in front of nginx.

Flyway runs inside the api at startup. A future one-shot migrate service can own that
step instead: start the api with `BEACON_FLYWAY_ENABLED=false` (`spring.flyway.enabled`)
and without `BEACON_MIGRATE_DB_PASSWORD`, so the long-running process never holds the
schema-owner credentials (BC-0123 F6). compose does not do this yet.

nginx also sets `X-Content-Type-Options`, `Referrer-Policy`, `X-Frame-Options: DENY`,
`Permissions-Policy` and a strict CSP (`script-src 'self'`, no inline scripts; the Vite
build has none because the modulePreload polyfill is off; `'unsafe-inline'` only under
`style-src` for React style attributes). Hashed `/assets/*` are cached immutable;
`index.html` is `no-cache`.

## Credentials policy

| Account | Password | Role | Where it comes from |
|---|---|---|---|
| `demo` | `demo-password` | USER | public contract fixture (`contracts/examples/createSession`); consent v1 (service only), board `synthetic-demo-1` at 40°, INTERMEDIATE, goals `[TECHNIQUE]` |
| `admin` | `BEACON_ADMIN_PASSWORD` from `.env` | ADMIN | the seed script registers it through the API, then runs `UPDATE users SET role='ADMIN'` inside the postgres container (the only way to set a role, ADR-0005). Never printed. |

Database passwords (`POSTGRES_PASSWORD`, `BEACON_MIGRATE_DB_PASSWORD`,
`BEACON_APP_DB_PASSWORD`) live only in `.env` (gitignored, gitleaks-scanned). The seed
script reads `BEACON_ADMIN_PASSWORD` in a subshell and passes it to `curl` through a file
in a `chmod 700` temp dir that is removed on exit; tokens never hit stdout.

Everything here is synthetic. The synthetic board is labelled as such in the UI ("Synthetic
demo board" stamp) and in the manifest (`synthetic: true`, `license_review_status:
SYNTHETIC`).

## Not public, by design (TM-26, TM-27)

- All host ports bind `127.0.0.1`. Nothing on this stack listens on the LAN.
- There is no approved public demo host (ADR-0007, "Approved public demo host" unset).
  Do not expose 3000/8080/5432 through a tunnel or port-forward.
- `ADMIN` is only ever created by `make seed-demo` on a laptop. A public demo (GAP-3)
  would need the `DEMO_MODE=public` work: synthetic-only accounts, no ADMIN, notes
  disabled, scheduled reset, TLS. None of that exists yet.
- The api container cannot write to the repo (jar mounted read-only, root FS read-only);
  `data/raw` is never mounted.

## Day-to-day

| Need | Command |
|---|---|
| Start / rebuild after code changes | `make dev` (rebuilds the jar and the web image; `--wait` blocks until healthy) |
| Stop, keep data | `make down` |
| Stop and drop the database and decision snapshots | `make reset-db` (volumes `pgdata`, `decisions` are removed; `init.sql` runs again on the next `make dev`) |
| Re-seed | `make seed-demo` (idempotent: import reports `imported routes=0`, existing users log in instead of registering, consent/preferences are re-applied, role stays ADMIN) |
| Logs | `docker compose --env-file .env -f infra/compose/docker-compose.yml --profile core logs -f api` (or `web`, `postgres`) |
| Status | `docker compose --env-file .env -f infra/compose/docker-compose.yml --profile core ps -a` |
| psql | `docker compose --env-file .env -f infra/compose/docker-compose.yml --profile core exec postgres psql -U beacon -d beacon` |
| Restart just the api (e.g. after a rebuilt jar) | `docker compose --env-file .env -f infra/compose/docker-compose.yml --profile core restart api` |
| Import another catalog | `make import FILE=<catalog.jsonl> [ACK=1]` — see the warning below |
| Vite dev server against this api | `pnpm --filter @beacon/web dev:api` (origin 5173 is CORS-allowed) |

The API's JSON logs never contain tokens, notes or bodies (TM-33); nginx's access log is
the stock format (path and status, no bodies).

## Troubleshooting

**Docker is not running** — `make dev` fails right after `BUILD SUCCESSFUL` with
`Cannot connect to the Docker daemon`. Start Docker Desktop and re-run.

**`Missing .env`** — `cp .env.example .env` and fill every line. A blank value fails with
`set <VAR> in .env` from compose; `make seed-demo` fails with
`BEACON_ADMIN_PASSWORD is unset or empty`.

**Port already in use** — `Bind for 127.0.0.1:8080 failed: port is already allocated`
(or 3000 / 5432). Find the owner with `lsof -nP -iTCP:8080 -sTCP:LISTEN`; a stray
`./gradlew bootRun`, a Vite preview or an old compose project are the usual culprits.
`make down` then `make dev`.

**`make dev` times out waiting for `api` to be healthy** — look at
`... logs api`. Typical causes: the jar is missing (`./gradlew :api:bootJar` failed
earlier), Flyway refused a migration (the `beacon_migrate` role or its password changed:
`make reset-db` re-initialises the database), or the laptop is slow and the 20 s start
period + 30 x 3 s retries were not enough (re-run `make dev`; containers are reused).

**`web` unhealthy or 502 on `/v1/...`** — the web container only starts after `api` is
healthy; a 502 later means `api` crashed or restarted. `... ps -a` then `... logs api`.

**`/v1/...` requests hang after `make import` or `make recompute-features` while the
stack is up** — both targets re-run `:api:bootJar`, which rewrites the jar that is
bind-mounted into the running api container. A JVM whose jar changes underneath it fails
lazy class loading (`NoClassDefFoundError`, worker thread dies, the client sees no
response) while `/health/ready` keeps passing. Fix: `... restart api` (or `make dev`,
which does not restart it on its own). `make seed-demo` avoids this by running the
importer jar directly without a rebuild.

**`make seed-demo` fails in the import step** — it needs a JDK 21 on the host
(`JAVA_HOME` or `PATH`): `Unable to locate a Java Runtime` on macOS means `JAVA_HOME`
is unset (Homebrew: `/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`).
Import exit codes are documented on the `import` target in the Makefile.

**`make seed-demo` says `HTTP 429`** — the API rate-limits public POSTs per client IP: a
burst of `beacon.http.public-burst` (default 5) refilling at
`beacon.http.public-write-limit-per-minute` (default 30, one token every two seconds), or
10 failed logins per 15 minutes per username and per IP. Wait a few seconds (or 15 minutes
after failed logins) and re-run.

**`HTTP 503` with `Retry-After: 1` on register or sign-in** — more than
`beacon.auth.max-concurrent-hashes` (default 3) password hashes were in flight for longer
than `beacon.auth.hash-wait` (2 s); the API refuses instead of running out of heap (BC-0123
F1). Retry.

**The explore page is empty after seeding** — the user must have preferences for
`synthetic-demo-1` (the seed sets them) and the board must be imported
(`select count(*) from routes;` in psql should be 300). A stale session token from a
database that was reset returns 401; sign out and in again.

**The web image build fails on `pnpm install --frozen-lockfile`** — the lockfile and
`apps/web/package.json` disagree; run `pnpm install` at the repo root and commit the
lockfile. The build context is the repo root filtered by `apps/web/Dockerfile.dockerignore`
(allowlist: `package.json`, `pnpm-lock.yaml`, `pnpm-workspace.yaml`, `contracts/`,
`apps/web/` minus `node_modules`, `dist`, test output and `.env*`).

## CI

`compose-smoke` (`.github/workflows/ci.yml`) writes a throwaway `.env` from
`openssl rand -hex 24`, runs `make dev` and `make seed-demo`, then curls
`/health/ready`, the SPA shell and `/v1/consent/current` through nginx on the published
ports, dumps compose logs on failure and always ends with `make reset-db`.

## E2E (BC-0121)

`make test-e2e` runs the Playwright vertical slice in `test/e2e/` against the stack above:
onboard a fresh user through the UI, browse the synthetic routes, build a session (decision
record resolved through `GET /v1/me/decisions/{id}`), log an attempt with a double-clicked
Save (one canonical row), correct, retract, log a second attempt, reload the history, confirm
`cold_start` has ended, and replay an `Idempotency-Key` directly against the API. It runs at
1280 (`chromium-desktop`) and 375 (`chromium-mobile`) with different users.

```sh
make dev && make seed-demo      # once; the suite never starts or stops the stack
make test-e2e                   # refuses with "run make dev && make seed-demo first" if :3000 is down
```

- Playwright comes from `apps/web`'s install (`pnpm --filter @beacon/web exec playwright test
  --config ../../test/e2e/playwright.config.ts`); there is no second `node_modules`.
- Users are `e2e_<timestamp>_<project><random>`, so the suite passes again on the same
  database without `make reset-db`. The seeded `demo` account is only read (`demo-login.spec.ts`).
- Artifacts (gitignored): `test/e2e/test-results/<spec>-<project>/` holds `trace.zip` and
  `video.webm` for every test (trace and video are always on) plus screenshots on failure;
  `test/e2e/playwright-report/index.html` is the HTML report
  (`pnpm --filter @beacon/web exec playwright show-report ../../test/e2e/playwright-report`).
- Runtime on the reference laptop (Apple Silicon, Docker Desktop, warm stack): `make test-e2e`
  7-8 s wall-clock for both projects (Playwright reports 6.3-6.8 s; the slice journey itself
  is 3.3-3.5 s per project), measured over 10 consecutive runs; the acceptance budget is
  10 minutes. In CI
  (`.github/workflows/e2e.yml`: nightly 03:00 UTC, pushes to `integration`/`main`, manual)
  the job writes a throwaway `.env`, runs `make dev && make seed-demo && make test-e2e`,
  uploads both artifact folders and ends with `make reset-db`.
