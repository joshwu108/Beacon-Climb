# ADR-0007 — Deployment and cost cap

**Status:** PROPOSED · **Task:** BC-0013 · **Date:** 2026-10-14

## Decision
- Everything runs via Docker Compose profiles on the documented development laptop: `core` (postgres, api, web), `ml`, `stream` (kafka, flink), `observability` (otel collector, tempo, prometheus, grafana).
- Resource caps in compose so benchmarks are reproducible (`cpus`, `mem_limit` per service recorded in reports).
- **Monthly spend cap: $0 by default.** Any cloud resource (VPS for a public demo, object storage, GPU) requires the human to write the cap into this ADR and approve the specific resource. No public host is approved. A later public demo requires a revised ADR, a specific resource and a human-approved monthly cap; synthetic data only.
- No Kubernetes, no Terraform in scope.
- Secrets: `.env` (gitignored) from `.env.example`; gitleaks in pre-commit and CI.

## Human fields
- Approved monthly cap: `____` (unset = $0)
- Approved public demo host: `____` (unset = none)
