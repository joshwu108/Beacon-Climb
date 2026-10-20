# Architecture

Stub. The authoritative architecture description is `planning/00-execution-master-plan.md` §2 (gitignored planning pack, held by the human) and the accepted decision records under `docs/adr/` (see `docs/adr/README.md`). This file will grow into the full architecture document under BC-0604; until then it only orients a reader.

## Where to look

| Question | Source |
|---|---|
| MVP shape (end of Sprint 2, Gate G1) | master plan §2.1 diagram |
| Full system (end of Sprint 10) | master plan §2.2 diagram |
| Why the MVP schema will not need rework | master plan §2.3 evolution path |
| Service boundaries and stack pins | `docs/adr/ADR-0001-service-boundaries.md` |
| Persistence, outbox, revision and idempotency | `docs/adr/ADR-0002-persistence-and-events.md` |
| Replay artifacts and decision records | `docs/adr/ADR-0003-replay-artifacts.md`, `contracts/decisions/` |
| Stream semantics and engine selection | `docs/adr/ADR-0004-stream-semantics.md` |
| Auth | `docs/adr/ADR-0005-auth.md` |
| ML inference transport | `docs/adr/ADR-0006-ml-inference-transport.md` |
| Deployment and cost | `docs/adr/ADR-0007-deployment-and-cost.md` |
| REST contract v1 | `contracts/openapi.yaml`, `contracts/README.md` |
| Event contracts v1 | `contracts/events/` |
| Threats, privacy and retention | `docs/security-threat-model.md`, `docs/privacy-and-retention.md` |
| Data rights | `docs/data-source-audit.md` |

## MVP in one paragraph

A Vite + React + TypeScript single-page app calls a Java 21 Spring Boot API over REST `/v1` (OpenAPI 3.1, bearer token). The API owns PostgreSQL 17 (Flyway migrations) holding users, consents, preferences, boards/routes/variants/holds/placements, attempts with idempotency keys and revisions, an attempt-event outbox that is written but not yet published, synchronously recomputed user feature snapshots, ranking policies, recommendation decisions and exposures. Candidate snapshots are content-addressed JSON files. The catalog comes from a committed synthetic fixture or, locally and gated by the data-rights review, a Kilter extract. There is no Kafka, Flink, Redis, ML service or neural model in the MVP; the heuristic ranker is a pure function over a versioned policy table, and every recommendation already writes a complete decision record.

## Full system in one paragraph

The outbox is read by a publisher into Kafka 4 (KRaft) topic `attempts.v1`; a Flink 2.2.1 DataStream job (ADR-0004 decision record) keyed by `user_id`, event-time and revision-aware, emits `climber-features.v1`, `stream-reconciliation.v1` and `stream-dlq.v1`; a materializer package in the API upserts feature snapshots idempotently. A FastAPI + PyTorch CPU service provides grade prediction and embeddings, batch-precomputed into PostgreSQL. The API also hosts deterministic experiment assignment, guardrails, a rollback state machine and the replay engine (faithful replay from stored snapshots, what-if via re-scoring). Observability is OpenTelemetry into Tempo, Prometheus and Grafana under a compose profile. A SQL recompute from canonical attempts is the batch parity oracle for the streamed snapshots.

Nothing in this file is a measured property of the system. See `docs/evidence-ledger.md`.
