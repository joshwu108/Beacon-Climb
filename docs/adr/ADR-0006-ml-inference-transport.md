# ADR-0006 — ML inference transport

**Status:** PROPOSED · **Task:** BC-0013 · **Date:** 2026-10-14

## Decision
- `services/ml` exposes HTTP/JSON endpoints; FastAPI exports OpenAPI to `contracts/ml-inference.yaml`; the Java client is generated from that file.
- Primary integration is **batch precompute** (BC-0207): model outputs for every catalog variant are stored in Postgres `route_model_outputs`; the ranker reads a table at request time. Live calls are the exception (what-if compare with an alternative model), guarded by a 200 ms total timeout and circuit breaker with `fallback_used=true`.
- Model artifacts are content-addressed; the loader validates the digest and runs a fixture inference before `READY` (BC-0205).

## Rejected for now
gRPC/protobuf (toolchain in two languages for one internal call); in-process Java inference (ONNX/DJL) — revisit only if live inference becomes a measured bottleneck.
