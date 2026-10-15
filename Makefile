.PHONY: dev down reset-db test test-api test-web test-ml lint fmt guards seed-demo import synthetic-board bench-catalog recompute-features

COMPOSE := docker compose --env-file .env -f infra/compose/docker-compose.yml --profile core

.env:
	@echo "Missing .env: copy .env.example to .env and set every value (see comments)." >&2; exit 1

# BC-0120: build the api jar, (re)build the web image and start the whole core profile
# (postgres, decisions-init, api, web); --wait returns once every healthcheck passes.
dev: .env
	./gradlew :api:bootJar
	$(COMPOSE) up -d --build --wait

down: .env
	$(COMPOSE) down

# Drops the Postgres volume so postgres/init.sql runs again on the next `make dev`.
reset-db: .env
	$(COMPOSE) down -v

test: test-api test-web test-ml

test-api:
	./gradlew :api:test

test-web:
	pnpm --filter @beacon/web test

# BC-0106: local latency check of GET /v1/routes and /v1/routes/{id} on the demo board over HTTP
# against the Testcontainers Postgres. Prints p50/p95/max and the hardware line; record both.
bench-catalog:
	./gradlew :api:test --tests '*CatalogBenchIT*' -Dbench=true --rerun

test-ml:
	uv run --directory services/ml pytest
	uv run --directory ml --no-project --with pytest==9.1.1 pytest; status=$$?; [ $$status -eq 0 ] || [ $$status -eq 5 ]

lint: guards
	./gradlew :api:spotlessCheck
	pnpm --filter @beacon/web lint
	uv run --directory services/ml ruff check .
	uv run --directory ml --no-project --with ruff==0.17.0 ruff check .

guards:
	bash test/guards/check-no-licensed-data.test.sh
	bash scripts/ci/check-no-licensed-data.sh

fmt:
	./gradlew :api:spotlessApply
	pnpm --filter @beacon/web fmt
	uv run --directory services/ml ruff format .
	uv run --directory ml --no-project --with ruff==0.17.0 ruff format --exclude datasets .

# BC-0120: idempotent demo seed against a running `make dev` stack: imports the synthetic
# board, creates demo/demo-password (consented, preferences set) and an ADMIN `admin`
# whose password is BEACON_ADMIN_PASSWORD from .env. Prints the URLs; safe to re-run.
seed-demo: .env
	bash scripts/seed-demo.sh

# BC-0105: `make import FILE=<catalog.jsonl> [ACK=1] [REPORT_DIR=reports/import]` loads a catalog
# v1 file into the compose Postgres (published on 127.0.0.1:5432). The api container runs on a
# read-only filesystem, so the importer runs the same jar on the host JVM with the .env secrets.
# Exit codes: 0 clean, 2 rows rejected (see report), 3 licence gate, 4 content_sha256 mismatch,
# 5 not a catalog v1 file. ACK=1 acknowledges the data-source audit for synthetic=false files.
import: .env
	@test -n "$(FILE)" || { echo "usage: make import FILE=<catalog.jsonl> [ACK=1]" >&2; exit 2; }
	./gradlew :api:bootJar
	set -a; . ./.env; set +a; \
	BEACON_DB_URL=jdbc:postgresql://127.0.0.1:5432/beacon \
	"$${JAVA_HOME:+$$JAVA_HOME/bin/}java" -jar services/api/build/libs/api-0.1.0.jar \
	  --spring.profiles.active=import --import.file=$(FILE) \
	  $(if $(ACK),--import.acknowledge-audit=true,) \
	  $(if $(REPORT_DIR),--import.report-dir=$(REPORT_DIR),)

# BC-0113: `make recompute-features [USER=<uuid>|all] [W=2026-10-14T14:00:00Z]` recomputes the
# climber-features.v1 snapshot (user_feature_snapshots, source SYNC) for one user or every
# non-erased account at the explicit cutoff W (default: now). Same host-JVM pattern as `import`.
# Prints `recomputed user_id=... cutoff_w=... feature_as_of=... checksum=...` per user.
# Exit codes: 0 all recomputed, 1 at least one user failed, 2 bad USER or W. USER is only
# honoured when given on the make command line (the login shell exports USER=<account name>).
recompute-features: .env
	./gradlew :api:bootJar
	set -a; . ./.env; set +a; \
	BEACON_DB_URL=jdbc:postgresql://127.0.0.1:5432/beacon \
	"$${JAVA_HOME:+$$JAVA_HOME/bin/}java" -jar services/api/build/libs/api-0.1.0.jar \
	  --spring.profiles.active=recompute --recompute.user=$(if $(filter command line,$(origin USER)),$(USER),all) \
	  $(if $(W),--recompute.w=$(W),)

# BC-0104: regenerate the SYNTHETIC demo catalog fixture (deterministic for the seed; the
# committed file must not change). Run as a script with -I because -I implies -P, which keeps
# the cwd off sys.path and so breaks `python -I -m datasets.synthetic.generate`.
synthetic-board:
	uv run --directory ml --no-project python -I datasets/synthetic/generate.py --seed 42 --routes 300 --out datasets/synthetic/fixtures/demo-board-v1.jsonl
