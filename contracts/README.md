# Beacon Climb contracts

`openapi.yaml` is CONTRACT-v1 (1.0.0) for the synthetic vertical slice. Each implemented operation has request and response fixtures under `examples/<operationId>/`. Planned paths carry `x-planned: true`, return 501 if called before implementation, and are excluded from client mocks. Run `npm ci --prefix test/contracts` before `./test/contracts/validate_examples.sh` in a clean clone. The package and lockfile are self-contained under `test/contracts/`. The script checks the implemented fixture bodies and parameters against the OpenAPI schemas plus replay artifacts. Error responses use `ErrorEnvelope`; `X-Request-Id` is returned on every response. `Idempotency-Key` and quoted `If-Match` are normative.

Changelog: **1.0.0** — initial 2026-10-14 contract. Human review and CONTRACT-v1 tag are pending; agents must not tag.
