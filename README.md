# Beacon Climb

Beacon Climb is a local-first climbing recommendation project. The current repository contains a runnable foundation with no catalog, accounts, attempts, recommendations, or trained model yet.

## Quick start

Install the pinned tools in `mise.toml` (Java 21, Node 22, pnpm 10, Python 3.12, uv) and start Docker Desktop. From the repository root:

```bash
pnpm install --frozen-lockfile
uv sync --directory services/ml
make dev
curl -sS localhost:8080/health/ready
make down
```

`make dev` builds the Spring Boot API and starts the `core` Compose profile: PostgreSQL and API. Readiness returns `{"status":"UP"}` only when PostgreSQL is reachable. The FastAPI scaffold lives at `services/ml/app/main.py` and is not part of the `core` profile.

Run checks with `make lint` and `make test`. Use `make test-api`, `make test-web`, or `make test-ml` for one suite; `make fmt` applies formatting. `make seed-demo` and `make import` are planned targets and exit with a message until BC-0120 and BC-0105 respectively. `pre-commit` hooks are configured in `.pre-commit-config.yaml`.

## Layout

- `services/api/`: Java 21 Spring Boot API with actuator health and Flyway wiring.
- `apps/web/`: Vite, React, TypeScript placeholder route at `/`.
- `services/ml/`: FastAPI health app at `/health`.
- `ml/`: Python package and test configuration; dataset work is owned separately.
- `infra/compose/`: local PostgreSQL and API profile.
- `contracts/` and `test/contracts/`: shared schemas and examples, owned by the integrator.
- `docs/`: architecture and project documents, owned by other agents.
- `planning/`: local, gitignored briefs and handoffs.
- `scripts/ci/`: ownership and licensed-data checks.

The [PRD](planning/BEACON_CLIMB_PRD.md) and [agent rules](AGENTS.md) describe the planned milestones and ownership boundaries. Performance, user counts, and service targets in planning files are planned until measured.
