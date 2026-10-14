# Beacon Climb: Security Threat Model (BC-0007)

**Status:** DRAFT, pending review by the Codex integrator and the human. **Ticket:** BC-0007. **Date:** 2026-10-14.
**Author:** Claude Code `security-reviewer`.
**Inputs:** PRD §5.2, §6.1, §6.2, §6.4, §8.3, §9.1, §10, §11; ADR-0001, ADR-0002, ADR-0003, ADR-0005, ADR-0007; `contracts/openapi.yaml` (CONTRACT-v1 1.0.0); `contracts/events/*.schema.json`; `contracts/decisions/decision-record.v1.schema.json`; `planning/01-backlog.csv`; `planning/04-test-and-evaluation-matrix.md` §3; `planning/05-risk-register.md`.
**Companion:** `docs/privacy-and-retention.md` covers data classes, retention, consent, erasure and demo/logging rules.

This document must not weaken PRD §10. Where it is stricter than an ADR, it says so and names the ADR to amend. The product stores no health, biometric, location or minors' data. `notes` is limited to 500 characters, is never logged, and is excluded from events and from public-mode exports.

Likelihood and impact use L/M/H, meaning the same as in `planning/05-risk-register.md`. They are judgments, not measurements. Rows marked **(verified)** cite a defect I reproduced against files in the repository on 2026-10-14. All other rows are design-time threats against code that does not exist yet.

## 1. Scope, trust boundaries and assets

### 1.1 Scope

In scope:
- the deployables from ADR-0001: `apps/web`, `services/api` (including the `materializer` package), `services/ml` and `services/stream`
- Postgres 17, Kafka (KRaft) and local file stores (`data/decisions`, `data/models`, `data/raw`)
- the observability stack from BC-0308
- CI (`.github/workflows`)
- the agent workflow (Claude/Codex sessions, `planning/handoffs`)
- an optional public demo host. ADR-0007 has not approved one, so none exists.

Out of scope until separately designed: Redis (deferred by ADR-0001), gRPC, external IdP, E07 photo capture. For E07, see `docs/privacy-and-retention.md` §1.3.

### 1.2 Trust boundaries (text diagram)

```text
            [TB8 optional public demo host: NOT APPROVED (ADR-0007 field unset)]
 Internet / LAN
 ---------------------------------------------------------------------------- TB1
   Browser SPA (apps/web)                 untrusted client
     sessionStorage: bearer token         (BC-0115)
     IndexedDB: attempt drafts incl notes (BC-0118)
        | HTTPS on any non-localhost host; bearer token
        v
 ---------------------------------------------------------------------------- TB1
   services/api (Spring Boot)  -- identity, attempts, ranking, decisions,
     |   |   |                     outbox, materializer, replay, privacy, admin
     |   |   +--TB4-- services/ml (FastAPI) --loads--> data/models/<ver>/  (TB5)
     |   +--TB2-- Postgres (source of truth: users, sessions, consent,
     |            attempts, outbox, snapshots, decisions, exposures)
     |   +------- data/decisions/<sha256>.json candidate snapshots   (TB5)
     +--TB3-- Kafka: attempts.v1 -> Flink (keyed state, checkpoints)
                     -> climber-features.v1 -> materializer -> Postgres
                     stream-dlq.v1 <- invalid events; scripts/dlq-redrive.sh
   OpenTelemetry -> collector -> Tempo / Prometheus / Grafana        (TB7)
 ---------------------------------------------------------------------------- TB6
   Developer laptop + AI agents: read untrusted dataset cards/READMEs, write
   planning/handoffs (gitignored), run git; GitHub Actions CI
   data/raw/*.sqlite (licensed Kilter data, local only)               (TB5)
```

| Boundary | Crossing | Trust assumption today |
|---|---|---|
| TB1 | browser to api | Client is hostile. Every `/v1/me/**` call resolves the user from the token (ADR-0005). |
| TB2 | api to Postgres | Least-privilege credentials are required by PRD §10. **Not met (verified):** compose uses the superuser `beacon` with fallback password `change-me`, published on all interfaces. See TM-27. |
| TB3 | api/outbox to Kafka to Flink to materializer | Kafka has no authentication in local compose. `producer: "api"` in the schema is a label, not authentication. |
| TB4 | api to ml | Internal HTTP. The ml service loads artifacts from disk, so artifact integrity matters (TM-14). |
| TB5 | processes to local files | Snapshot files and model artifacts are integrity-checked by digest (ADR-0003, BC-0205). `data/raw` is licensed and must never leave the laptop (D1). |
| TB6 | agents/humans to git/CI | Agents read untrusted text. CI runs on `pull_request`, not `pull_request_target`, and uses no repository secrets today. |
| TB7 | services to observability | Must receive no tokens, notes or raw bodies (TM-33). |
| TB8 | public demo host | Does not exist. Synthetic data only if the human approves it (TM-26). |

### 1.3 Assets

| Asset | Class | Primary store |
|---|---|---|
| Credentials: password hashes, session token hashes, DB password | secret | Postgres `users`, `sessions`; `.env` |
| Attempt history (attempts, revisions, idempotency keys, `notes`) | personal | Postgres |
| Consent records | personal | Postgres |
| Preferences | personal | Postgres |
| Feature snapshots and the `climber-features.v1` changelog | personal (derived) | Postgres, Kafka, Flink state |
| Decision ledger, exposures, candidate snapshots | personal (derived) | Postgres, `data/decisions` |
| Licensed Kilter catalog and derived geometry/weights | third-party licensed | `data/raw`, `data/catalog`, `data/models` (local only) |
| Model artifacts | integrity-critical; may embed personal data (Model B) | `data/models` |
| Experiment and rollback state, admin role | integrity-critical | Postgres |
| CI integrity, repository history | integrity-critical | GitHub |
| Agent decision integrity (what agents commit/approve) | integrity-critical | developer laptop |

## 2. Threat table

Test-ID convention: `planning/04-test-and-evaluation-matrix.md` §3 currently has **no test IDs**, only rows of the form `Threat | Test | Task`. A test reference is therefore written as `§3:<exact text of the §3 Threat cell>`. The verification script in §5 checks that this text exists in §3. GAP-5 proposes stable IDs (SEC-01 to SEC-09 for the existing rows in order, SEC-10 onward for the proposed rows below). "GAP → see §Gaps" in the test column means §3 has no row for the threat. §4 proposes one. Where the backlog or §1 of the matrix already has a related test, the mitigation cell says so.

| threat | asset | entry point | likelihood | impact | mitigation | mitigating task ID | test ID |
|---|---|---|---|---|---|---|---|
| TM-01 Unauthorized access to histories (PRD §10) | attempts, decisions, snapshots, export | `GET /v1/me/attempts`, `GET /v1/me/decisions/{id}`, `/replay`, `/compare`, `GET /v1/me/export` | M | H | User resolved only from the bearer token. Every query has `WHERE user_id = :token_user` in SQL, not a post-filter. Cross-user access returns 404, never 403 (ADR-0005, PRD §8.3). The internal snapshot URI is never returned (BC-0112). | BC-0107, BC-0109, BC-0112, BC-0402, BC-0403, BC-0412 | §3:Cross-user history read / IDOR |
| TM-02 IDOR on attempts/decisions/history via path IDs | attempts, decisions | `PATCH /v1/me/attempts/{attempt_id}`, `/v1/me/decisions/{decision_id}{,/replay,/compare}` | M | H | Ownership predicate inside the same SQL statement as the read/write. IDs are UUIDv7 (ADR-0002), whose time prefix leaks creation time, so IDs are treated as non-secret and protection relies only on authorization. A PATCH on a foreign ID returns 404 before If-Match is evaluated, so the revision of another user's row cannot be probed. | BC-0109, BC-0112, BC-0402, BC-0403 | §3:Cross-user history read / IDOR |
| TM-03 Broken ownership authorization on admin endpoints | experiment state, rollback | `/v1/admin/experiments`, `/v1/admin/experiments/{id}/rollback` | L | H | Role `ADMIN` is assigned only by the seed script (ADR-0005). Non-admin gets 403. Rollback writes an audit row and is idempotent. `ADMIN` is never seeded on any public host (TM-26). | BC-0408, BC-0410 | §3:Admin endpoints |
| TM-04 Forged attempts via API (missing/forged/expired token) | attempts | `POST /v1/me/attempts` | M | M | Opaque 256-bit `SecureRandom` token. Only its sha256 is stored. 24 h TTL. 401 on missing, forged, expired or revoked tokens (ADR-0005). | BC-0107 | §3:Forged/expired token |
| TM-05 Forged attempt events injected directly into Kafka | attempts.v1, features, downstream decisions | Kafka listener in compose (`infra/compose/kafka.yml`, BC-0301) | L local / H if broker reachable | H | Kafka listeners stay on the compose-internal network. Any host port is published on `127.0.0.1` only. No SASL-less broker on any non-laptop host. Consumers accept only schema-valid events. `producer` is a label, not authentication. BC-0301 acceptance must add these. | BC-0301 | GAP → see §Gaps |
| TM-06 Replay of HTTP attempt submissions (duplicate or retried POST) | attempts, features | `POST /v1/me/attempts` | H | M | `Idempotency-Key` required (1–128 chars), scoped `(user_id, key)`, with a request fingerprint. Same key and body returns 200 with the original result. Same key with a different body returns 409 (ADR-0002). The web client generates one key per draft and reuses it on retry (BC-0118). Also covered by matrix §1 "FR-03 double submit". | BC-0109, BC-0118 | §3:Idempotency abuse across users |
| TM-07 Replay of attempt events (redelivery, outbox republish, DLQ redrive, deliberate re-publish) | features, snapshots | Kafka consumers; `scripts/dlq-redrive.sh` | H | M | Consumers dedupe on `(attempt_id, attempt_revision)`. A higher revision supersedes. The materializer keeps a monotonic offset/watermark guard. Redrive re-validates the schema, refuses events whose `user_id` has `deleted_at` set, and writes an audit log. Matrix §1 FR-07/FR-08 cover convergence, not the security case. | BC-0304, BC-0305, BC-0302 | GAP → see §Gaps |
| TM-08 Idempotency abuse (cross-user key collision, key/body confusion, unbounded key storage) | attempts | `POST /v1/me/attempts` | M | M | Key scope includes `user_id`, so two users with the same key get two independent attempts. Same key with a different body returns 409. Rate limit on POST (BC-0122). Keys are deleted with the account. The contract should restrict the key charset (§6 C-02) so it can be logged safely. | BC-0109, BC-0122 | §3:Idempotency abuse across users |
| TM-09 Missing idempotency or unsafe retries on non-attempt writes | outbox/Kafka, rollback, erasure, consent, ledger | outbox publisher; `POST .../rollback`; `DELETE /v1/me`; `POST /v1/me/consent`; `POST /v1/me/sessions/recommendations` | M | M | Idempotent producer plus consumer dedupe (BC-0301). Rollback is an idempotent state machine (BC-0408). `DELETE /v1/me` is idempotent: a repeat returns the same pending erasure, and the hard-delete job is resumable. Consent POST upserts on `(user_id, consent_version)`. A retried recommendation POST creates a new `decision_id` by design (documented; rate-limited), and the same `session_id` plus context yields the same ranking (BC-0111). | BC-0301, BC-0408, BC-0412, BC-0107, BC-0111 | GAP → see §Gaps |
| TM-10 Injection through user notes (SQL, stored XSS, log injection, spreadsheet-formula injection in export) | DB, browsers, logs, exports | `notes` in `POST`/`PATCH /v1/me/attempts` | M | M | `maxLength: 500` in the contract. Parameterized queries only. Notes are rendered as text, never HTML. Nonce CSP (BC-0603). Notes are never logged (BC-0122). Export is JSON only. Any future CSV export escapes leading `= + - @`. | BC-0109, BC-0118, BC-0122, BC-0603 | §3:Oversized notes / injection |
| TM-11 Notes leaking into events, DLQ or logs under a renamed key **(verified contract weakness)** | notes | outbox payload builder to `attempts.v1.attributes` | M | M | `contracts/events/attempts.v1.schema.json` line 100 sets `attributes.additionalProperties: true` and blocks only the exact key `notes` (`not: {required: [notes]}`, line 101). AJV accepts the keys `note`, `Notes`, `comment`, `free_text` and `notes_text` (see §5.2). Fix: an attributes allowlist (`additionalProperties: false`), which needs a contract change (§6 C-01). Meanwhile, BC-0109 must build the outbox payload from an explicit field list, and the BC-0302 contract tests must include renamed-key fixtures. | BC-0109, BC-0302 | GAP → see §Gaps |
| TM-12 Unlicensed dataset redistribution (PRD §10) | Kilter catalog, derived geometry, weights trained on it | commits, docs, model releases, demo host, export | M | H | D1: local-only import behind `--i-have-read-the-audit` (BC-0103). `data/` and `*.sqlite` are gitignored. CI guard (BC-0010). No raw/derived geometry or weights trained on it are published without written human sign-off (Gate D0). User export lists `route_variant_id`s, never licensed geometry. The licence inventory lives in BC-0603. | BC-0002, BC-0103, BC-0010, BC-0603 | §3:Unlicensed data |
| TM-13 Licensed data accidentally tracked in git **(verified guard bypass)** | Kilter data | `git add` by humans/agents; alternate file formats | M | H | `scripts/ci/check-no-licensed-data.sh` lines 13–23 flag only names containing `.sqlite`, and `.jsonl` files whose first line has `"synthetic": false`. A scratch repo with `kilter.db` (SQLite magic header), `kilter.csv`, a `.jsonl` without the flag, `.ndjson`, and `weights.pt` passes (§5.2). `.claude/settings.json` line 4 allows `Bash(git add *)`, which also matches `git add -f data/...`. Fix: an allowlist guard plus a local pre-commit hook plus an agent deny rule (FU-1, FU-5). | BC-0010, BC-0016 | §3:Unlicensed data |
| TM-14 Compromised model files (PRD §10): pickle code execution, tampered weights | ml host, ranking integrity | `data/models/<version>/`, `make register-model` | L | H | Digest verification at load, plus a fixture inference before `READY`. A mismatch returns `REJECTED` and the previous version stays active (BC-0205). No pickle formats: safetensors/ONNX/JSON for GBM. `torch.load(..., weights_only=True)` only. Artifacts are never fetched from the network at serve time. Broken-schema rejection is tested (BC-0409; matrix §1 FR-12). | BC-0205, BC-0409 | GAP → see §Gaps |
| TM-15 Model artifact containing training rows or memorizing an individual | attempt histories | model artifacts, training manifests, eval reports | L now (Gate D1 NO-GO default) / M if Model B ships | H | Exception policy in `docs/privacy-and-retention.md` §6. No instance-based models that store rows. No per-user embedding/ID parameters without an erasure path. Manifests hold HMAC'd IDs. Committed reports are aggregate only (PRD §6.4). | BC-0205, BC-0209, BC-0506 | GAP → see §Gaps |
| TM-16 Secrets in logs (PRD §10): passwords, tokens, `Authorization` header, DSN with password | credentials | app logs, OTel spans, CI logs | M | H | Log-scrubbing filter and a log-capture test (BC-0107). Structured logs with a pseudonymous `user_id` only, no request bodies for auth/attempts (BC-0122). OTel header/body capture disabled (BC-0308). The DSN is never logged. | BC-0107, BC-0122, BC-0308 | §3:Secrets in logs |
| TM-17 Leaked `.env` or secrets in agent handoffs or transcripts (agent-specific) | DB password; any future API key | `planning/handoffs/*.md` (gitignored, so never scanned by gitleaks), pasted terminal output, CI logs | M | M today (dummy local credentials) / H once any real secret exists | gitleaks pre-commit covers only staged files (BC-0009), so gitignored handoffs are not scanned. Add a `gitleaks dir planning/` local scan (GAP-2). The handoff template forbids env dumps. `.env.example` holds placeholders only (currently `POSTGRES_PASSWORD=change-me`). | BC-0009, GAP → see §Gaps | GAP → see §Gaps |
| TM-18 Secrets committed to the repository | credentials | git history | M | H | gitleaks pre-commit (BC-0009). **Verified gap:** matrix §3 says "gitleaks pre-commit + CI history scan", but no workflow runs gitleaks (`grep gitleaks .github/workflows/*.yml` returns nothing), and pre-commit can be skipped with `--no-verify`. Add a CI full-history gitleaks job (FU-1). | BC-0009, BC-0010, BC-0603 | §3:Secrets in repo |
| TM-19 CI secrets and CI supply chain | `GITHUB_TOKEN`, future secrets, build integrity | `.github/workflows/ci.yml`, `contracts.yml` | L | M | Neither workflow has a `permissions:` block, so the token scope falls back to repository defaults. Actions are pinned by tag (`@v4`, `@v6`), not by commit SHA. Fix: `permissions: contents: read`, SHA pins, no `pull_request_target`, no secrets in PR jobs (FU-1). | BC-0010 | GAP → see §Gaps |
| TM-20 Dependency vulnerabilities | all services | Gradle, pnpm, uv lockfiles | M | M | OWASP dependency-check, `pnpm audit` and `pip-audit` (BC-0603), but BC-0603 is Sprint 11. Pull the scans into CI in Sprint 1 (FU-1). Lockfiles are frozen (`--frozen-lockfile`, `--locked` already in CI). | BC-0603, BC-0010 | §3:Vulnerable deps |
| TM-21 Token theft or replay with opaque bearer tokens | sessions, every `/me` resource | XSS reading `sessionStorage` (BC-0115), tokens in URLs/logs, plaintext HTTP on a hosted demo | M | H | 24 h TTL (2 h on a public demo, see privacy doc §7). sha256 at rest. Revocation on logout, password change and erasure (ADR-0005). The contract has **no logout endpoint** (§6 C-04). Nonce CSP (BC-0603). Tokens never in query strings. TLS on any non-localhost host. A replayed revoked token returns 401. | BC-0107, BC-0115, BC-0603 | §3:Forged/expired token |
| TM-22 Password hashing misconfiguration, login brute force, hashing-cost DoS | password hashes, api CPU/memory | `POST /v1/auth/session` | M | M | argon2id with explicit parameters at or above the OWASP Password Storage Cheat Sheet minimum (m=19 MiB, t=2, p=1; verify at BC-0107 against the then-current sheet). Spring's `defaultsForSpringSecurity_v5_8()` is believed to use m=16 MiB (verify at BC-0107), so it must not be used unreviewed. 10 attempts per 15 min per username and per IP (ADR-0005). The per-username limit lets anyone lock out a shared demo account, so the public demo uses per-visitor accounts (privacy doc §7). `AuthRequest.password` has no `maxLength`, so add 128 (default, pending) together with a body-size limit (§6 C-02). | BC-0107, BC-0122 | GAP → see §Gaps |
| TM-23 DLQ poisoning | `stream-dlq.v1`, redrive path, features | malformed or oversized events; `scripts/dlq-redrive.sh` | L | M | DLQ records carry the original bytes (`original_value_base64`), so treat them as untrusted. `error_message` never echoes payload values (it could echo notes). Cap the record size. Redrive re-validates, rejects erased users, and is audited. DLQ retention drops to 14 days (default, pending; ADR-0002 says 30; see privacy doc §5.3). | BC-0302 | GAP → see §Gaps |
| TM-24 Decision-record tampering (ledger row or snapshot file) | `recommendation_decisions`, `data/decisions/*.json` | DB write access; local filesystem | L | M | `candidate_set_digest` check returns `DIGEST_MISMATCH` (ADR-0003). Tampered scores make `match=false` (BC-0404; matrix §1 FR-10). The app DB role has no `UPDATE` on the ledger except through the erasure job. The digest sits in the same DB as the record, so it catches corruption but not an attacker with DB write access. An HMAC with a key held outside Postgres is an optional hardening for BC-0401. | BC-0401, BC-0402, BC-0404 | GAP → see §Gaps |
| TM-25 Denial of service (PRD §10) | api, Postgres, disk | unbounded arrays (`exclude_route_variant_ids` has no `maxItems`), unbounded strings (`username`, `route_variant_id`), recommendation POSTs that each write a snapshot file, export | M | M | Rate limits return 429 (BC-0122; its acceptance already tests the 61st request per minute). Contract `maxItems`/`maxLength` (§6 C-02). Request body size limit. Snapshot-store quota and an alert. | BC-0122 | GAP → see §Gaps |
| TM-26 Leakage from public demo / demo host exposure (PRD §10) | anything visitors type; admin functions | public VPS (none approved; ADR-0007 "Approved public demo host" is unset) | L now / H if deployed | H | Synthetic accounts only. No real data on any public host. Demo banner. Notes disabled. No `ADMIN`. Random per-deploy secrets. Short tokens. Scheduled reset. TLS. Full rules in privacy doc §7. No ticket owns this. | GAP → see §Gaps | GAP → see §Gaps |
| TM-27 Local datastore and API exposed on the developer's network **(verified)** | Postgres (the founder's real logbook once BC-0501 runs), api | `infra/compose/docker-compose.yml` lines 8–10, 20–21, 24–27 | M | H once real data is loaded / M while synthetic-only | Ports `"5432:5432"` and `"8080:8080"` bind all host interfaces. The password falls back to `change-me`, and the role is the Postgres superuser. The api container mounts the whole repo read-write (`../../:/app`, including `.git`, `.env`, `data/raw`). Fix: publish on `127.0.0.1`, require the password with `${POSTGRES_PASSWORD:?}`, use a non-superuser app role with a separate migration role, and mount only the jar read-only (FU-2). | BC-0120 | GAP → see §Gaps |
| TM-28 Prompt injection via dataset README, dataset card or third-party docs (PRD §10, agent-specific) | agent actions: commits, data-rights conclusions, config | HF dataset card, BoardLib README, SQLite string columns, issue text, web pages read by agents | M | H | Treat all fetched content as data, never as instructions. Quote it, do not follow it. Never execute dataset-provided scripts. Use `python -I` and isolated directories (already in the BC-0002 approach). Data-rights conclusions require human sign-off (Gate D0). Agents may not push or tag (`.claude/settings.json` deny list). No ticket gives agents a written rule for this. | BC-0002, GAP → see §Gaps | GAP → see §Gaps |
| TM-29 Erasure inconsistency: residual personal data across stores | all personal data | `DELETE /v1/me` and the hard-delete job | M | H | Checklist in privacy doc §5. BC-0412 enumerates stores dynamically. Kafka aging argument with corrected DLQ retention. Features tombstone and Flink state clear, which no ticket owns (GAP-4). Decision-record contract cannot represent an erased record (§6 C-03). Matrix §1 "US-08 export/delete" exists. | BC-0412, GAP → see §Gaps | GAP → see §Gaps |
| TM-30 Processing after consent withdrawal | attempts; research use | `POST /v1/me/attempts` or recommendations after `DELETE /v1/me/consent`; training/eval exports | M | M | 403 `CONSENT_REQUIRED` on attempt and recommendation writes (BC-0107; the contract declares 403 on both). Withdrawn users are excluded from any training/eval export (BC-0501 importer). Matrix §1 "FR-03 profile + consent" covers only the API part. | BC-0107, BC-0501 | GAP → see §Gaps |
| TM-31 Stolen token used to delete or export an account | the whole history | `DELETE /v1/me`, `GET /v1/me/export` | L | H | Step-up password re-authentication for `DELETE /v1/me` and export (contract shape is still `x-planned`; §6 C-06). The 30-day grace allows an operator restore. Export is rate-limited and sent with `Cache-Control: no-store`. | BC-0412 | GAP → see §Gaps |
| TM-32 Client-side residue on a shared device (drafts with notes, token) | notes, token | browser `IndexedDB` (BC-0118), `sessionStorage` (BC-0115) | M | M | Drafts hold notes only until synced. Both stores are cleared on logout and on successful `DELETE /v1/me`. | BC-0118, BC-0115 | GAP → see §Gaps |
| TM-33 Observability leakage (trace attributes, metric labels, unbounded log retention) | user IDs, request data | OTel, Prometheus, Grafana, Docker `json-file` logs | M | M | No `user_id` as a metric label. No header or body capture. Retention defaults in privacy doc §8. Docker log rotation (FU-2). | BC-0308, BC-0122 | §3:Secrets in logs |

## 3. Residual-risk notes (not separate threats)

- With fewer than about 3 real users (Gate D1 default), de-identification does not work: any "anonymized" decision stub or aggregate is trivially linkable to the founder. Erasure therefore deletes, not anonymizes (privacy doc §5).
- `rng_seed = sha256(policy_version|user_id|session_id)` (ADR-0003) is a linkable function of `user_id`. It is removed from erased stubs.
- Kafka time retention deletes whole segments. A record survives up to roughly `retention.ms` plus `segment.ms` (upstream default `segment.ms` is 7 days; verify for the pinned Kafka at BC-0301). This is why the privacy doc sets `segment.ms` explicitly.

## 4. Gaps

### 4.1 Threats with no existing task: PROPOSED tickets

All entries are **PROPOSED**. None exists in `planning/01-backlog.csv`. The integrator assigns IDs.

| Gap | PROPOSED title | Suggested epic | Allowed paths | Closes |
|---|---|---|---|---|
| GAP-1 | PROPOSED: Agent untrusted-content rule. Treat dataset cards, READMEs, issue text and fetched pages as data. No execution of dataset-provided code. Data-rights conclusions need human sign-off. | E00 Foundation | `AGENTS.md`, `CLAUDE.md`, `.claude/agents/*.md`, `.codex/agents/*.toml` (human-owned; human approval required) | TM-28 |
| GAP-2 | PROPOSED: Local secret scan of gitignored agent artifacts (`gitleaks dir planning/`, `make scan-secrets`). Handoff template line forbidding env/credential dumps. | E00 Foundation | `scripts/ci/scan-local-secrets.sh`, `Makefile` (target only), `.gitleaks.toml` | TM-17 |
| GAP-3 | PROPOSED: Public demo mode (only if the human approves a host in ADR-0007). `DEMO_MODE=public` refuses to start unless every account is `synthetic=true`. Banner. Notes disabled. No ADMIN. 2 h tokens. Per-visitor ephemeral accounts. Scheduled reset. TLS proxy. | E06 Launch proof | `infra/compose/demo.yml`, `services/api/src/main/java/.../platform/demo/**`, `apps/web/src/components/demo-banner/**`, `docs/runbooks/demo-host.md` | TM-26 |
| GAP-4 | PROPOSED: Erasure propagation through streaming. Privacy control event (new topic or new event type; contract change, integrator approval). Flink keyed-state clear. `climber-features.v1` tombstone at soft delete. Savepoint retention and re-erasure on restore. Topic configs (`segment.ms`, DLQ retention 14 d, `max.compaction.lag.ms`). | E04 Replay & experiments (with E03 streaming lead) | `contracts/events/privacy-control.v1.schema.json` (contract change), `services/stream/src/main/java/.../privacy/**`, `services/api/src/main/java/.../privacy/streaming/**`, `infra/compose/kafka.yml` (topic configs only) | TM-29, TM-23 |
| GAP-5 | PROPOSED: Security test IDs in the matrix. Assign SEC-01 to SEC-09 to the existing §3 rows in order. Add SEC-10 to SEC-27 (table below). | E00 Foundation | `planning/04-test-and-evaluation-matrix.md` (human/integrator-owned, gitignored) | every "GAP" in the test column |

PROPOSED §3 test rows (GAP-5):

| Proposed ID | Threat rows | Test (pass criterion) |
|---|---|---|
| SEC-10 | TM-05, TM-27 | `docker compose config` shows Kafka/Postgres/api ports bound to `127.0.0.1` only. Producing to `attempts.v1` from outside the compose network fails. |
| SEC-11 | TM-07, TM-23 | The same `(attempt_id, revision)` delivered 10x gives one contribution and the same checksum. Redrive of an erased user's DLQ record is rejected and audited. A redriven malformed record goes back to the DLQ. |
| SEC-12 | TM-09 | `DELETE /v1/me` twice gives one erasure record. Consent POST twice with the same version gives one active record. Rollback twice gives one transition. A publisher retry after a simulated ack loss gives a single feature contribution. |
| SEC-13 | TM-11 | Outbox payloads whose attributes contain any key outside the allowlist (`note`, `Notes`, `comment`, ...) are rejected by the contract test. A log capture after a POST with notes contains no notes text. |
| SEC-14 | TM-14 | A pickle artifact, a digest mismatch and a wrong input schema are each `REJECTED`. The previous version keeps serving. |
| SEC-15 | TM-15 | An artifact scan finds no `user_id`/`attempt_id` strings, no per-user parameter tables and no embedded training frames. |
| SEC-16 | TM-22 | Stored hash parameters equal the configured argon2id parameters. A password longer than the max returns 422 without hashing. The 11th login in 15 min returns 429. |
| SEC-17 | TM-25 | Oversized arrays/strings return 422. An oversized body returns 413. The 61st POST per minute returns 429. |
| SEC-18 | TM-24 | A modified `ranked_results` gives `match=false`. A modified snapshot file gives `DIGEST_MISMATCH`. The app role's `UPDATE` on the ledger is denied outside the erasure job. |
| SEC-19 | TM-26 | `DEMO_MODE=public` with a non-synthetic account fails to start. The banner is present. The notes field is absent. No ADMIN exists. Tokens expire at the configured demo TTL. |
| SEC-20 | TM-27 | Compose fails to start without `POSTGRES_PASSWORD`. The app role is not superuser. The api container has no write access to the repo. |
| SEC-21 | TM-28 | Manual: data-audit handoff includes the untrusted-content checklist. A planted instruction in a fixture dataset card is reported, not followed. |
| SEC-22 | TM-29 | Erasure completeness: the store enumeration in privacy doc §5.4 has 0 residual rows/files/records, including Kafka topic config assertions and Flink state (once E03 exists). |
| SEC-23 | TM-30 | After consent revoke, attempt and recommendation POSTs return 403 `CONSENT_REQUIRED`. The user is absent from training/eval exports. |
| SEC-24 | TM-31 | `DELETE /v1/me` and export without fresh password re-authentication return 401/403. Export carries `Cache-Control: no-store`. |
| SEC-25 | TM-32 | Playwright: after logout or delete, `sessionStorage` and IndexedDB drafts are empty. |
| SEC-26 | TM-19 | Workflow lint: every workflow has `permissions:`. Every third-party `uses:` is SHA-pinned. No `pull_request_target`. |
| SEC-27 | TM-17 | `make scan-secrets` exits non-zero on a planted fake key in `planning/handoffs/`. |

### 4.2 Follow-ups on existing tasks (task exists but its acceptance misses the threat, or a verified defect exists)

| Follow-up | Existing task | Change requested | Closes |
|---|---|---|---|
| FU-1 | BC-0010 (done) | Replace the denylist guard with an allowlist: fail on any tracked `*.sqlite*`, `*.db`, `*.csv`, `*.parquet`, `*.ndjson`, `*.jsonl` (unless the first-line manifest has `synthetic: true`), and on `*.pt`, `*.safetensors`, `*.onnx`, `*.pkl` outside allowlisted fixture paths. Scan the PR commit range, not just HEAD. Add a CI gitleaks full-history job. Add dependency scans now (from BC-0603). Add `permissions: contents: read` and SHA-pin actions. Make the contracts workflow fail closed if the harness is missing. | TM-13, TM-18, TM-19, TM-20 |
| FU-2 | BC-0120 (owns `infra/compose/**`) | Publish on `127.0.0.1`. `${POSTGRES_PASSWORD:?}`. Non-superuser app role plus migration role. Read-only jar mount. Docker log rotation. | TM-27, TM-33 |
| FU-3 | BC-0005 / BC-0006 / BC-0401 | Contract changes listed in §6 (integrator approval). | TM-11, TM-21, TM-22, TM-25, TM-29, TM-31 |
| FU-4 | BC-0301 | Kafka listener binding. `message.timestamp.type=LogAppendTime` on `attempts.v1` (so a back-dated `occurred_at` cannot shorten or extend retention). Explicit `segment.ms`. Outbox purge job (ADR-0002 says "outbox rows age out" but assigns no job). The publisher skips soft-deleted users. | TM-05, TM-29 |
| FU-5 | BC-0016 | Human-owned `.claude/settings.json`: add deny rules `Bash(git add -f*)`, `Bash(git add --force*)`. Run the licensed-data guard as a local pre-commit hook. | TM-13 |

## 5. Verification

### 5.1 Every task ID exists in the backlog and every test reference exists in §3

Command, run from the repository root against this file:

```bash
python3 -I - docs/security-threat-model.md <<'PY'
import csv, re, sys
root = '.'
ids = {r['id'] for r in csv.DictReader(open(root + '/planning/01-backlog.csv', newline=''))}
m = open(root + '/planning/04-test-and-evaluation-matrix.md').read()
sec3 = m.split('\n## 3.')[1].split('\n## 4.')[0]
labels = {l.split('|')[1].strip() for l in sec3.splitlines()
          if l.startswith('|') and not l.startswith('|---') and not l.startswith('| Threat |')}
doc = open(sys.argv[1]).read()
hdr = '| threat | asset | entry point | likelihood | impact | mitigation | mitigating task ID | test ID |'
print('header exact:', hdr in doc)
rows = [l for l in doc.splitlines() if l.startswith('| TM-')]
GAP = 'GAP → see §Gaps'
bad = []; n_ids = set(); n_gap_task = n_gap_test = 0
for l in rows:
    c = [x.strip() for x in l.strip().strip('|').split('|')]
    if len(c) != 8: bad.append((c[0][:6], 'cols=%d' % len(c))); continue
    task, test = c[6], c[7]
    bcs = re.findall(r'BC-\d{4}', task); n_ids |= set(bcs)
    miss = [b for b in bcs if b not in ids]
    labs = [t.strip() for t in re.findall(r'§3:([^;]+)', test)]
    badl = [t for t in labs if t not in labels]
    n_gap_task += GAP in task; n_gap_test += GAP in test
    if not (bcs or GAP in task) or miss or not (labs or GAP in test) or badl:
        bad.append((c[0][:6], miss, badl))
all_doc_ids = set(re.findall(r'BC-\d{4}', doc))
print('rows:', len(rows), '| distinct task IDs in table:', len(n_ids),
      '| rows with GAP in task col:', n_gap_task, '| rows with GAP in test col:', n_gap_test)
print('task IDs missing from backlog:', sorted(i for i in all_doc_ids if i not in ids) or 'none')
print('§3 labels available:', len(labels), '| problems:', bad or 'none')
PY
```

Output (recorded 2026-10-14):

```text
header exact: True
rows: 33 | distinct task IDs in table: 31 | rows with GAP in task col: 4 | rows with GAP in test col: 19
task IDs missing from backlog: none
§3 labels available: 9 | problems: none
```

### 5.2 Reproductions of the verified defects

TM-13 (licensed-data guard bypass), in a throwaway scratch git repo outside this tree:

```bash
S=$(mktemp -d) && cd "$S" && git init -q . && \
printf '{"route_uuid":"abc","frames":"p1r12p2r13"}\n' > kilter-routes.jsonl && \
printf '{"provider":"kilter","rows":1}\n' > catalog-nosyntheticflag.jsonl && \
printf 'uuid,frames,angle\nabc,p1r12,40\n' > kilter.csv && \
printf 'SQLite format 3\0' > kilter.db && \
printf '{"synthetic": false}\n' > data.ndjson && printf 'x' > weights.pt && git add -A && \
bash /Users/joshuawu/Beacon-Climb/scripts/ci/check-no-licensed-data.sh; echo "exit=$?"
```

```text
Licensed-data guard passed: no tracked SQLite or non-synthetic JSONL manifest
exit=0
```

TM-11 (notes denylist), using the AJV already installed under `test/contracts/node_modules` and the committed `valid-attempt-recorded.json`, adding one attribute key at a time:

```text
notes        invalid (rejected)
note         VALID (accepted)
Notes        VALID (accepted)
comment      VALID (accepted)
free_text    VALID (accepted)
notes_text   VALID (accepted)
```

TM-18: `grep -n gitleaks .github/workflows/*.yml` printed nothing (the shell fell through to `no gitleaks in workflows`).

## 6. Contract changes needed (integrator approval; not made by this ticket)

| ID | Contract | Change | Why |
|---|---|---|---|
| C-01 | `contracts/events/attempts.v1.schema.json` | `attributes.additionalProperties: false` (allowlist). Adding an optional attribute later becomes an explicit schema minor change. This conflicts with `contracts/events/README.md` "Attributes may contain new fields", so the integrator must decide. | TM-11 |
| C-02 | `contracts/openapi.yaml` | `AuthRequest.password.maxLength` (128, default pending). `username` `maxLength`/pattern. `Idempotency-Key` pattern `^[A-Za-z0-9._:-]{1,128}$`. `RecommendationRequest.exclude_route_variant_ids.maxItems`. `route_variant_id` `maxLength`. Declare `401`/`413`/`429` responses. | TM-08, TM-22, TM-25 |
| C-03 | `contracts/decisions/decision-record.v1.schema.json` (BC-0401 v2) | `erased` is `const: false` (line 332) and `user_id`/`session_id`/`rng_seed` are required, so an erased stub cannot be represented. Add an erased-stub variant: `decision_id`, coarse `requested_at` date, versions, `experiment_id`/`served_variant`, `erased: true`; no `user_id`, `session_id`, `rng_seed`, `request_constraints` or `ranked_results`. Also constrain `candidate_snapshot.user_features` (currently `{"type":"object"}`, line 140) to the `climber-features.v1` aggregates. | TM-29 |
| C-04 | `contracts/openapi.yaml` | Logout endpoint (`DELETE /v1/auth/session`). ADR-0005 promises revocation on logout, but no operation exists. | TM-21 |
| C-05 | `contracts/openapi.yaml` | `Attempt` (response) omits `notes` while `AttemptCreate`/`AttemptPatch` accept it, so notes are write-only to the owner. Either return them to the owner (owner-only field) or stop collecting them. Data minimization forbids collecting data the subject can never read except through export. | privacy |
| C-06 | `contracts/openapi.yaml` | `DELETE /v1/me` and `GET /v1/me/export` (both `x-planned`): define step-up re-authentication, `202` plus erasure status for delete, `no-store` for export. | TM-31 |
| C-07 | `contracts/openapi.yaml` `ConsentRequest`/`Consent` | Add `scopes` (`service` required, `research` optional and default off) and `text_sha256` before any research use beyond the founder's own data (BC-0501, BC-0511). | TM-30 |
