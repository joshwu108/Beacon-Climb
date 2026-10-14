# ADR-0001 — Service boundaries, stack and footprint

**Status:** PROPOSED · **Task:** BC-0003 · **Date:** 2026-10-14

## Context

PRD §5.2 names seven services and three datastores. Capacity is one human plus at most three concurrently writing agents. Every deployable adds compose wiring, CI jobs, contract surface and integration risk.

## Decision

1. Single monorepo (PRD §14 layout), Gradle Kotlin DSL multi-project for Java, pnpm workspace for web, uv workspaces for Python.
2. Deployables, by milestone:
   - `services/api` — Java 21 LTS, Spring Boot 4.1.1 (Java 21 is supported; Boot-managed Flyway 12.4.0 and Testcontainers 2.0.5; springdoc 3.1.1 supports Boot 4). Fall back to Spring Boot 3.5.16 if a build or integration test exposes a blocker., Postgres 17. Contains packages `catalog`, `identity`, `attempts`, `features`, `ranking`, `recommendations`, `outbox`, `materializer`, `replay`, `experiments`, `privacy`, `admin`, `platform`.
   - `apps/web` — Vite + React 19 + TypeScript SPA. Next.js rejected: no SEO, authenticated app, SSR adds a server with no benefit.
   - `services/ml` — Python 3.12, FastAPI, PyTorch CPU; `ml/` holds datasets/train/evaluate/configs/tests. Added Sprint 3–4.
   - `services/stream` — Java, Flink 2.2.1 DataStream with `flink-connector-kafka` 5.0.0. The proposed Flink 2.3.x / connector 4.0.x pairing is incompatible according to Flink’s release matrix; Kafka Streams remains the runtime fallback if the Kafka 4 KRaft integration test fails.. Added Sprint 5.
3. **Materializer is a package in `services/api`** (profile `materializer`), owned by the streaming lead by path. Rationale: same JPA entities, same transaction manager, idempotent upsert is a Spring Kafka listener.
4. **Deferred with triggers:**
   - Redis: add only if measured recommendation p95 > 300 ms is attributable to Postgres feature reads (BC-0601).
   - gRPC: add only if BC-0601 measures JSON transport overhead above 10% of ranking latency on the target laptop.
   - Separate materializer service: add only if BC-0311 measures feature-topic p95 consumer lag above 60 seconds inside the API process after ordinary tuning.
   - Kubernetes/Terraform: only if BC-0601 documents that a single approved host cannot meet its load target and a new ADR-0007 plus human spend approval authorizes more than one host.
5. Contracts live in `contracts/` (OpenAPI 3.1, JSON Schema for events/catalog/decisions/model manifests). Clients are generated (openapi-generator for Spring interfaces, openapi-typescript for web, FastAPI exports its own spec).

## Consequences

- Four deployables max by Sprint 6; one datastore plus Kafka.
- Streaming lead and backend lead share a Gradle module; CODEOWNERS enforces package-path ownership.
- Version pins are recorded in `.tool-versions`/`mise.toml` and this ADR's appendix with the verification date.

## Alternatives rejected

- Modular monolith with ML in-process (JVM): would require Java inference (DJL/ONNX); keeps Python research and serving apart from the portfolio goal of a Java/Python split.
- Python backend (FastAPI for everything): loses the Java/Spring/Flink portfolio objective.

## Appendix — verified pins (checked 2026-10-14)

| Component | Planned line | Verified version | Official source URL | Date |
|---|---|---|---|---|
| Java | 21 LTS | 21 (supported by Boot 4.1.1) | https://docs.spring.io/spring-boot/system-requirements.html | 2026-10-14 |
| Spring Boot | 4.1.x | 4.1.1 | https://spring.io/projects/spring-boot | 2026-10-14 |
| PostgreSQL | 17 | 17.11 (`postgres:17` tracks 17) | https://www.postgresql.org/docs/release/17.11/ | 2026-10-14 |
| Flyway / Testcontainers / springdoc | latest compatible | 12.4.0 / 2.0.5 / 3.1.1 | https://docs.spring.io/spring-boot/appendix/dependency-versions/coordinates.html ; https://github.com/springdoc/springdoc-openapi/releases | 2026-10-14 |
| Node / pnpm | 22 LTS / 10 | 22.23.3 / 10.34.7 | https://nodejs.org/en/download/archive/v22.23.3 ; https://www.npmjs.com/package/pnpm/v/10.34.7 | 2026-10-14 |
| Vite / React | 6 / 19 | 6.4.4 / 19.3.1 | https://www.npmjs.com/package/vite/v/6.4.4 ; https://www.npmjs.com/package/react/v/19.3.1 | 2026-10-14 |
| Python / uv | 3.12 / latest | 3.12.15 / 0.13.1 | https://www.python.org/downloads/release/python-31215/ ; https://github.com/astral-sh/uv/releases/tag/0.13.1 | 2026-10-14 |
| FastAPI / PyTorch | latest / CPU wheel | 0.143.0 / 2.14.0 CPU | https://fastapi.tiangolo.com/release-notes/ ; https://github.com/pytorch/pytorch/releases ; https://pytorch.org/get-started/locally/ | 2026-10-14 |
| Kafka | 4.x KRaft | 4.3.1 (`apache/kafka:4.3.1`) | https://kafka.apache.org/community/downloads/ | 2026-10-14 |
| Flink / connector | 2.3.x / 4.0.x-2.0 | **incompatible**; fallback 2.2.1 / 5.0.0 | https://flink.apache.org/downloads/ | 2026-10-14 |

The official Flink matrix lists connector 4.0.1 for Flink 2.0.x and connector 5.0.0 for Flink 2.1.x/2.2.x, but no released connector for 2.3.x. The selected 2.2.1/5.0.0 pair is declared compatible by Flink. That matrix does not certify Kafka 4 broker interoperability; BC-0303 must run a Kafka 4.3.1 KRaft producer/consumer test. On failure use Kafka Streams as named in ADR-0004. Boot's dependency BOM establishes Flyway and Testcontainers versions; springdoc's own FAQ states 3.x supports Boot 4. BC-0009 must still run an integration build. Python 3.12.15 is source-only; the container base must supply it or build it, with 3.12.10 as the binary-install fallback.
