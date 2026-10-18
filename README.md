# Beacon Climb

Beacon Climb is a local-first climbing recommendation project. The repository runs the vertical slice end to end on one laptop: a synthetic demo board, local accounts with consent, attempt logging with corrections, a deterministic heuristic recommender with a decision ledger, and a React client. There is no trained model and no real catalog data in the repository; everything shipped here is synthetic and labelled as such.

## Quick start

Install the pinned tools in `mise.toml` (Java 21, Node 22, pnpm 10, Python 3.12, uv) and start Docker Desktop. On macOS with Homebrew, `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home` if no system Java is installed. From the repository root:

```bash
pnpm install --frozen-lockfile
uv sync --directory services/ml
cp .env.example .env            # then set every value, e.g. openssl rand -hex 24 per line
make dev                        # api jar + web image + postgres/migrate/api/web, waits for healthchecks
make seed-demo                  # synthetic board, demo user (demo / demo-password), admin user
open http://127.0.0.1:3000      # open as 127.0.0.1, not localhost (docs/runbooks/dev-stack.md)
make test                       # api (Testcontainers Postgres), web unit, ml
make test-e2e                   # Playwright slice against the running stack
make down                       # stop, keep the database; `make reset-db` drops it
```

`make dev` refuses to start while any `.env` value is empty. If a database volume from an earlier checkout exists on this machine (the compose project is named `compose` in every checkout), `make dev` fails with `password authentication failed for user "beacon_migrate"` in the `migrate` service; run `make reset-db` once and start again. The full runbook, credentials policy and troubleshooting are in `docs/runbooks/dev-stack.md`.

Run checks with `make lint` and `make test`. Use `make test-api`, `make test-web`, or `make test-ml` for one suite; `make fmt` applies formatting. `make import FILE=…` loads another catalog file and `make recompute-features` rebuilds the feature snapshots (see the Makefile comments). `pre-commit` hooks are configured in `.pre-commit-config.yaml`.

## Layout

- `services/api/`: Java 21 Spring Boot API: identity and consent, catalog, attempts with an outbox, feature snapshots, heuristic recommendations and the decision ledger; Flyway migrations run from the one-shot `migrate` compose service.
- `apps/web/`: Vite, React, TypeScript client (onboarding, explore, session, attempts) with MSW mocks for the contract fixtures.
- `services/ml/`: FastAPI health app at `/health`.
- `ml/`: Python package and test configuration; dataset work is owned separately.
- `infra/compose/`: local PostgreSQL, migrate, API and web (`core` profile).
- `contracts/` and `test/contracts/`: shared schemas and examples, owned by the integrator.
- `docs/`: architecture, ADRs, runbooks, reviews and the evidence ledger (`docs/evidence-ledger.md`, the only place a measured number may be cited from).
- `planning/`: local, gitignored briefs and handoffs.
- `scripts/ci/`: ownership and licensed-data checks.

The [PRD](planning/BEACON_CLIMB_PRD.md) and [agent rules](AGENTS.md) describe the planned milestones and ownership boundaries. Performance, user counts, and service targets in planning files are planned until measured.
