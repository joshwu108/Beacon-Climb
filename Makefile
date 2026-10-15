.PHONY: dev down test test-api test-web test-ml lint fmt guards seed-demo import

dev:
	./gradlew :api:bootJar
	docker compose -f infra/compose/docker-compose.yml --profile core up -d

down:
	docker compose -f infra/compose/docker-compose.yml --profile core down

test: test-api test-web test-ml

test-api:
	./gradlew :api:test

test-web:
	pnpm --filter @beacon/web test

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

seed-demo:
	@echo "not implemented until BC-0120" >&2; exit 1

import:
	@echo "not implemented until BC-0105" >&2; exit 1
