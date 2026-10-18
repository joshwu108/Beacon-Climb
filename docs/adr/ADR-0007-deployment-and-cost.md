# ADR-0007 — Deployment and cost cap

**Status:** PROPOSED · **Task:** BC-0013 · **Date:** 2026-10-14

## Decision
- Everything runs via Docker Compose profiles on the documented development laptop: `core` (postgres, api, web), `ml`, `stream` (kafka, flink), `observability` (otel collector, tempo, prometheus, grafana).
- Resource caps in compose so benchmarks are reproducible (`cpus`, `mem_limit` per service recorded in reports).
- **Monthly spend cap: $0 by default.** Any cloud resource (VPS for a public demo, object storage, GPU) requires the human to write the cap into this ADR and approve the specific resource. No public host is approved. A later public demo requires a revised ADR, a specific resource and a human-approved monthly cap; synthetic data only.
- No Kubernetes, no Terraform in scope.
- Secrets: `.env` (gitignored) from `.env.example`; gitleaks in pre-commit and CI.

## Approved exception: Modal for ML training (human approval 2026-10-15)
- **Scope:** model *training and evaluation* runs only (BC-0203/0204 for Model A, BC-0504/0505 for Model B). Inference (`services/ml`), the API, the web app, Kafka/Flink and all demos stay local under Compose. Modal is never in the request path.
- **Same entrypoint, same evidence.** Remote runs invoke the identical `python -I -m ml.train.run --config ...` entrypoint through a thin wrapper (BC-0213, `make train-remote`). Every run, local or remote, writes the same manifest (git sha, config hash, dataset manifest id, seeds, hardware block, metrics JSON). Evidence-ledger rows record `environment: modal:<gpu-type>` so hardware is never misreported.
- **Data-transfer gate.** Uploading the Kilter-derived catalog or any real attempt history to Modal is a transfer to a third-party processor. The human signed the rights table and approved Kilter training on Modal on 2026-10-18 (`docs/data-source-audit.md` section 7, Modal exception). The wrapper still refuses non-synthetic manifests unless `--rights-signed` is passed and the audit file carries the signature line; uploads exclude every personal column; artifacts stay private and return to `data/models/`; nothing trained on Kilter data is published. Real attempt histories (Model B) are not covered by this approval.
- **Secrets:** `MODAL_TOKEN_ID` / `MODAL_TOKEN_SECRET` live in `.env` only; never in compose files, CI logs or handoffs. CI does not run remote training.
- **Cap:** remote training stays within Modal's included monthly credits unless the human writes a higher number below. Agents report estimated and actual cost per run in the handoff; any single run estimated above the remaining cap is not started.

## Human fields
- Approved monthly cap: `____` (unset = $0 beyond Modal's included credits)
- Approved public demo host: `____` (unset = none)
- Modal account approved: **yes (2026-10-15)**; monthly Modal cap: `____` (unset = included credits only)
