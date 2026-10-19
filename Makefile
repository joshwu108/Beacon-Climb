.PHONY: dev down reset-db test test-api test-web test-ml lint fmt guards seed-demo import synthetic-board bench-catalog recompute-features test-e2e stream-up stream-down outbox-status dlq-status dlq-redrive

COMPOSE := docker compose --env-file .env -f infra/compose/docker-compose.yml --profile core
# BC-0301/BC-0303: core plus the stream profile, merged over docker-compose.yml: kafka.yml
# (kafka, kafka-init, the api override that enables the outbox publisher) and flink.yml.
COMPOSE_STREAM := docker compose --env-file .env -f infra/compose/docker-compose.yml -f infra/compose/kafka.yml -f infra/compose/flink.yml --profile core --profile stream

.env:
	@echo "Missing .env: copy .env.example to .env and set every value (see comments)." >&2; exit 1

# BC-0120: build the api jar, (re)build the web image and start the whole core profile
# (postgres, decisions-init, api, web); --wait returns once every healthcheck passes.
dev: .env
	./gradlew :api:bootJar
	$(COMPOSE) up -d --build --wait

down: .env
	$(COMPOSE) down

# Drops the Postgres volume so postgres/init.sql runs again on the next `make dev`. Uses the
# stream file set so `kafkadata` goes too (an outbox reset without the broker log would leave
# already-published events behind); harmless when the stream services were never started.
reset-db: .env
	$(COMPOSE_STREAM) down -v

# BC-0301: core + Kafka 4.3.1 (KRaft) + topic bootstrap (+ the Flink session cluster from
# flink.yml, BC-0303), with the api recreated so its outbox publisher is enabled
# (BEACON_STREAM_ENABLED=true comes from kafka.yml). Kafka host listener 127.0.0.1:9094.
# `make dev` afterwards recreates the api with the publisher disabled again.
stream-up: .env
	./gradlew :api:bootJar
	$(COMPOSE_STREAM) up -d --build --wait

stream-down: .env
	$(COMPOSE_STREAM) down

# BC-0301: the reconciliation query over the outbox, run through psql in the postgres container
# (the SQL twin of OutboxReconciliation and the beacon_outbox_* gauges on /prometheus).
outbox-status: .env
	$(COMPOSE) exec -T postgres psql -U beacon -d beacon -c "SELECT count(*) AS unpublished_rows, coalesce(extract(epoch FROM (now() - min(created_at))), 0)::int AS oldest_unpublished_age_s, count(*) FILTER (WHERE created_at < now() - interval '60 seconds') AS unpublished_over_60s FROM attempt_event_outbox WHERE published_at IS NULL;"

test: test-api test-web test-ml

test-api:
	./gradlew :api:test

test-web:
	pnpm --filter @beacon/web test

# BC-0121: Playwright vertical slice against the RUNNING compose stack (`make dev && make
# seed-demo` first; nothing is started here). Runs from apps/web's Playwright install so there
# is no second node_modules. Artifacts: test/e2e/test-results (trace, video) and
# test/e2e/playwright-report (HTML), both gitignored. Docs: docs/runbooks/dev-stack.md "E2E".
test-e2e:
	@curl -fsS -o /dev/null http://127.0.0.1:3000/health/ready \
	  || { echo "e2e: the dev stack is not up on 127.0.0.1:3000 - run make dev && make seed-demo first" >&2; exit 1; }
	@curl -fsS -o /dev/null http://127.0.0.1:3000/v1/consent/current \
	  || { echo "e2e: the api is not answering through nginx - run make dev && make seed-demo first" >&2; exit 1; }
	pnpm --filter @beacon/web exec playwright test --config ../../test/e2e/playwright.config.ts

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

# BC-0309: `make reconcile` repairs the users with open reconciliation_events rows (attempts.v1
# events the stream job judged late beyond its 24 h window, stored by the api's
# stream-reconciliation.v1 consumer under BEACON_STREAM_ENABLED=true). Same host-JVM pattern as
# `recompute-features`: for each such user the SYNC snapshot is recomputed at W = now through
# FeatureRecomputeService and the rows are closed RECONCILED (checksum changed) or ALREADY_CURRENT
# (the attempts hook had already included the late event). The STREAM row is not touched.
# Prints `reconciled user_id=... rows=... cutoff_w=... checksum=... resolution=...` per user and a
# `reconcile summary ...` line. Exit codes: 0 every user repaired (or nothing open), 1 at least
# one user ended in ERROR (its rows stay open for the next run).
.PHONY: reconcile

reconcile: .env
	./gradlew :api:bootJar
	set -a; . ./.env; set +a; \
	BEACON_DB_URL=jdbc:postgresql://127.0.0.1:5432/beacon \
	"$${JAVA_HOME:+$$JAVA_HOME/bin/}java" -jar services/api/build/libs/api-0.1.0.jar \
	  --spring.profiles.active=reconcile

# BC-0302: DLQ operator targets (docs/runbooks/dlq.md). Both run the api jar's `redrive` profile
# on the host JVM against the compose Kafka host listener (127.0.0.1:9094, override with
# BEACON_STREAM_BOOTSTRAPSERVERS); that profile has no datasource, so Postgres is never touched.
# `dlq-status` prints the record count and the last 20 records (event_id, error_code, attempt).
# `dlq-redrive EVENTS=<id,id>|ALL [DRY_RUN=1] [TOPIC=stream-dlq.v1]` republishes the ORIGINAL
# bytes of each selected DLQ record to its original topic with dlq.attempt = previous + 1 and
# dlq.redriven_from = the DLQ event_id, one `redrive event_id=... result=...` audit line each.
# Exit codes: 0 every selected record published (or dry run), 1 something was skipped, 2 usage.
dlq-status: .env
	./gradlew :api:bootJar
	bash scripts/dlq-redrive.sh --list $(if $(TOPIC),--topic $(TOPIC),)

dlq-redrive: .env
	@test -n "$(EVENTS)" || { echo "usage: make dlq-redrive EVENTS=<id,id,...>|ALL [DRY_RUN=1] [TOPIC=stream-dlq.v1]" >&2; exit 2; }
	./gradlew :api:bootJar
	bash scripts/dlq-redrive.sh --events "$(EVENTS)" $(if $(DRY_RUN),--dry-run,) $(if $(TOPIC),--topic $(TOPIC),)

# BC-0104: regenerate the SYNTHETIC demo catalog fixture (deterministic for the seed; the
# committed file must not change). Run as a script with -I because -I implies -P, which keeps
# the cwd off sys.path and so breaks `python -I -m datasets.synthetic.generate`.
synthetic-board:
	uv run --directory ml --no-project python -I datasets/synthetic/generate.py --seed 42 --routes 300 --out datasets/synthetic/fixtures/demo-board-v1.jsonl

# BC-0303: Flink session cluster (infra/compose/flink.yml, part of COMPOSE_STREAM above) and the
# feature job. `make stream-up` starts jobmanager + taskmanager with the rest of the stream
# profile; the job itself is submitted separately so the fat jar build stays explicit.
.PHONY: stream-submit stream-job-status stream-job-cancel

# Builds the fat jar (mounted read-only into the jobmanager at /opt/beacon/libs, like the api
# jar) and submits it detached. Re-running submits a second copy: cancel first (stream-job-cancel).
stream-submit: .env
	./gradlew :stream:fatJar
	$(COMPOSE_STREAM) exec jobmanager flink run -d /opt/beacon/libs/stream.jar

stream-job-status: .env
	$(COMPOSE_STREAM) exec jobmanager flink list

# Cancels every running job (JOB=<id> to cancel one). Checkpoints are retained on cancellation.
stream-job-cancel: .env
	@if [ -n "$(JOB)" ]; then $(COMPOSE_STREAM) exec jobmanager flink cancel $(JOB); \
	else for id in $$($(COMPOSE_STREAM) exec jobmanager flink list -r 2>/dev/null | awk '/RUNNING/ {print $$4}'); do \
	  $(COMPOSE_STREAM) exec jobmanager flink cancel $$id; done; fi

# BC-0311: SYNTHETIC load generator and freshness measurement (scripts/loadgen, docs/benchmarks).
# `make loadgen USERS=20 RATE=10 DURATION=60 [OUT=reports/loadgen/x] [EVIDENCE=path]` runs
# loadgen.py against the running stack (reports/ is gitignored); `make freshness
# EVENTS=<events.jsonl> [OUT=dir]` recomputes summary.json/summary.txt from a stored run.
# `make loadgen-stack [STREAM=0]` is `make stream-up` (or `make dev` with STREAM=0) with
# infra/compose/loadgen.yml merged last, which raises the api's per-user/per-IP write limits for
# a load run; a report measured on it must say so (loadgen.py records the api environment).
.PHONY: loadgen freshness loadgen-stack

COMPOSE_LOADGEN := docker compose --env-file .env -f infra/compose/docker-compose.yml \
  $(if $(filter 0,$(STREAM)),,-f infra/compose/kafka.yml -f infra/compose/flink.yml) \
  -f infra/compose/loadgen.yml --profile core $(if $(filter 0,$(STREAM)),,--profile stream)

loadgen-stack: .env
	./gradlew :api:bootJar
	$(COMPOSE_LOADGEN) up -d --build --wait

loadgen:
	python3 -I scripts/loadgen/loadgen.py --users $(or $(USERS),10) --rate $(or $(RATE),5) --duration $(or $(DURATION),30) \
	  $(if $(OUT),--out $(OUT),) $(if $(EVIDENCE),--evidence-copy $(EVIDENCE),)

freshness:
	@test -n "$(EVENTS)" || { echo "usage: make freshness EVENTS=<events.jsonl> [OUT=dir]" >&2; exit 2; }
	python3 -I scripts/loadgen/freshness.py $(EVENTS) $(if $(OUT),--out $(OUT),)

# BC-0308: observability profile (infra/compose/observability.yml merged last over the stream
# file set: Prometheus 127.0.0.1:9090, Grafana 127.0.0.1:3001 (admin / GRAFANA_ADMIN_PASSWORD
# from .env), Tempo 127.0.0.1:3200, the one-shot otel-agent-init and the api/jobmanager/
# taskmanager overrides that load the OpenTelemetry Java agent). `observability-up` recreates
# the api and the Flink roles with the agent; `observability-down` removes only the
# observability containers (volumes kept), after which `make stream-up` or `make dev` recreates
# the api/Flink without the agent. `observability-check` asserts the metric names on the api's
# /prometheus endpoint (test/observability/metrics-presence.sh; STREAM=0 when only the core
# profile is running). Runbook: docs/runbooks/observability.md.
.PHONY: observability-up observability-down observability-check

COMPOSE_OBS := $(COMPOSE_STREAM) -f infra/compose/observability.yml --profile observability

observability-up: .env
	$(COMPOSE_OBS) up -d --wait

observability-down: .env
	$(COMPOSE_OBS) rm -sf prometheus grafana tempo otel-agent-init

observability-check:
	$(if $(filter 0,$(STREAM)),BEACON_OBS_STREAM=0,) bash test/observability/metrics-presence.sh

# BC-0306: `make test-parity [USER=<uuid>|all] [OUT=docs/evidence/BC-0306/parity-<timestamp>.json]`
# runs the batch parity oracle of ADR-0004 (scripts/parity/parity.sh, api jar profile `parity`,
# host JVM against 127.0.0.1:5432, SELECT only, no Flyway) over every non-erased user with a
# STREAM row (or the given one): batch recompute at W = the row's cutoff_w vs the materialized
# aggregates and checksum. Writes the JSON report to OUT (counts only in the summary; synthetic_/
# e2e_/demo users only, otherwise refuses with exit 2) and prints one `parity summary ...` line.
# Exit codes: 0 every compared user EQUAL, 1 any DIFF or SAME_W_MISMATCH, 2 usage/refusal/error.
# USER is only honoured when given on the make command line (the login shell exports USER).
# BC-0307 calls this after every chaos scenario (test/integration/parity/README.md).
.PHONY: test-parity

test-parity: .env
	./gradlew :api:bootJar
	bash scripts/parity/parity.sh --user $(if $(filter command line,$(origin USER)),$(USER),all) $(if $(OUT),--out $(OUT),)


# BC-0103: Kilter splits sqlite -> catalog v1 under data/catalog/kilter-<sha12>/ (gitignored,
# LOCAL RESEARCH ONLY; docs/data-source-audit.md section 7). Never commit the output.
KILTER_DB ?= data/raw/kilter_splits.sqlite
KILTER_MANIFEST ?= ml/datasets/manifests/kilter-0a0bc5b1bed1.json
extract-kilter:
	uv run --directory ml --no-project python -I datasets/kilter/extract.py --db ../$(KILTER_DB) --manifest ../$(KILTER_MANIFEST) --acknowledge-audit

# BC-0201: freeze the Model A dataset (dedupe across splits, seal test) from the BC-0103 output.
# Rows go to data/datasets/model-a-<id>/ (gitignored); the hash-and-count manifest is committed.
KILTER_CATALOG_DIR ?= data/catalog/kilter-0a0bc5b1bed1
freeze-dataset:
	uv run --directory ml --no-project python -I datasets/model_a/build.py --catalog-dir ../$(KILTER_CATALOG_DIR) --db ../$(KILTER_DB)

# BC-0203: Model A baselines. `val` fits everything on train, scores val and freezes the
# selection; `test` refits the frozen candidates and unseals test once (logged).
# Reports: reports/model-a/<run>/metrics.json (gitignored); model: data/models/model-a/<run>/.
MODEL_A_CONFIG ?= configs/model_a_baselines.json
train-model-a-val:
	uv run --directory ml --group train python -I -m beacon_ml.train.run --config $(MODEL_A_CONFIG) --stage val
train-model-a-test:
	@if [ -z "$(SELECTION)" ]; then echo "usage: make train-model-a-test SELECTION=reports/model-a/<run>/selection.json" >&2; exit 2; fi
	uv run --directory ml --group train python -I -m beacon_ml.train.run --config $(MODEL_A_CONFIG) --stage test --selection ../$(SELECTION)

# BC-0204: Deep Sets candidate. `val` trains with early stopping on val and freezes a
# hash-checked checkpoint under data/models/model-a-deepsets/<run>/; `test` compares it with the
# frozen gradient-boosting selection on a single unsealing of test.
DEEPSETS_CONFIG ?= configs/model_a_deepsets.json
train-deepsets-val:
	uv run --directory ml --group train --group torch-cpu python -I -m beacon_ml.train.run_deepsets --config $(DEEPSETS_CONFIG) --stage val
train-deepsets-test:
	@if [ -z "$(CANDIDATE)" ] || [ -z "$(SELECTION)" ]; then echo "usage: make train-deepsets-test CANDIDATE=data/models/model-a-deepsets/<run>/candidate.json SELECTION=reports/model-a/<run>/selection.json" >&2; exit 2; fi
	uv run --directory ml --group train --group torch-cpu python -I -m beacon_ml.train.run_deepsets --config $(DEEPSETS_CONFIG) --stage test --candidate ../$(CANDIDATE) --baseline-selection ../$(SELECTION)

# BC-0205: package a trained Model A artifact into data/models/registry/<VERSION>/ (gitignored)
# and register it (READY/REJECTED after hash and fixture checks) in model_versions.
package-model:
	@if [ -z "$(KIND)" ] || [ -z "$(SOURCE)" ] || [ -z "$(VERSION)" ] || [ -z "$(RUN)" ] || [ -z "$(METRICS)" ]; then echo "usage: make package-model KIND=sklearn-hgb|torch-deepsets SOURCE=data/models/... VERSION=model-a-... RUN=<run id> METRICS=docs/evidence/..." >&2; exit 2; fi
	uv run --directory ml --group train --group torch-cpu python -I -m beacon_ml.registry --kind $(KIND) --source-dir ../$(SOURCE) --model-version $(VERSION) --training-manifest-id model-a-100d08f359ca --training-run-id $(RUN) --metrics-ref $(METRICS)
register-model: .env
	@if [ -z "$(VERSION)" ]; then echo "usage: make register-model VERSION=model-a-... [ACTIVATE=1]" >&2; exit 2; fi
	set -a; . ./.env; set +a; \
	BEACON_ML_DB_URL="postgresql://beacon_app:$${BEACON_APP_DB_PASSWORD}@127.0.0.1:5432/beacon" \
	uv run --directory services/ml python -I -m app.register --dir ../../data/models/registry/$(VERSION) $(if $(ACTIVATE),--activate,)

# BC-0207: batch-precompute a registered model's outputs for every variant of a Kilter board
# into route_model_outputs (idempotent: a re-run writes nothing new).
precompute-model: .env
	@if [ -z "$(VERSION)" ]; then echo "usage: make precompute-model VERSION=model-a-... [BOARD=kilter-l1-s10]" >&2; exit 2; fi
	set -a; . ./.env; set +a; \
	BEACON_ML_DB_URL="postgresql://beacon_app:$${BEACON_APP_DB_PASSWORD}@127.0.0.1:5432/beacon" \
	uv run --directory services/ml python -I -m app.precompute --dir ../../data/models/registry/$(VERSION) --board $(or $(BOARD),kilter-l1-s10)
