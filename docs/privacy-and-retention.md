# Beacon Climb: Privacy, Consent, Retention and Erasure Policy (BC-0007)

**Status:** DRAFT. **Every retention value in this document is a default, pending human sign-off.** None of them is a measurement. **Ticket:** BC-0007. **Date:** 2026-10-14.
**Author:** Claude Code `security-reviewer`. **Companion:** `docs/security-threat-model.md`. Threat IDs `TM-xx`, gaps `GAP-n`, follow-ups `FU-n` and contract notes `C-0n` refer to that document.
**Baseline (decided, not reopened here):** local pseudonymous demo accounts, argon2id, opaque 24 h tokens (ADR-0005); `attempts.v1` 7-day retention, `climber-features.v1` compacted, soft delete plus 30-day grace then hard delete (ADR-0002); $0 spend and no public host (ADR-0007).
This policy may not weaken PRD §10. Where it differs from an ADR, it is stricter and names the ADR to amend.

## 1. Scope and prohibited data

### 1.1 Prohibited data classes (MVP and v1)
- **No health, medical, injury, pain, biometric, body-measurement, location or minors' data** is collected, inferred or stored. The product makes no medical, rehabilitation, injury-prevention or training-safety claim (PRD §10, master plan §8).
- `perceived_effort` (integer 1–10, PRD §6.1, `contracts/openapi.yaml`) is classified as a **self-reported training-load rating**, not health data. It must never be used to infer physical or medical status, and no free-text or pain field may accompany it. *Human sign-off requested on this classification.*
- Adults only. The UI does not ask for age, so the consent text states the service is for adults (PRD §10).
- No email address, real name, phone or IP address is stored in the database. `username` is chosen by the user, and the onboarding UI tells the user not to use their real name or email.

### 1.2 `notes`
- At most 500 characters (`maxLength: 500` in `AttemptCreate`/`AttemptPatch`).
- **Never logged**: not at any level, not in error envelopes, not in traces.
- **Excluded from all events.** `attempts.v1` excludes it today only by a single-key denylist (TM-11, verified). This policy requires an attribute allowlist (C-01).
- **Excluded from public-mode exports** (§9.2) and from benchmarks, eval reports, demo fixtures and screenshots.
- Included in the owner's own data-subject export (§9.1).

### 1.3 Out of scope until separately designed
- Redis (deferred by ADR-0001). If added: per-user keys have a TTL of 24 h or less (default, pending) and are deleted at soft delete.
- E07 photo route capture (master plan §7b). Photos can carry EXIF GPS location and faces. Any E07 ADR must strip EXIF before storage, must not store faces, and must add a data class here before implementation.

## 2. Data inventory, retention and erasure step per class

T0 is the time `DELETE /v1/me` is accepted. "Hard delete" means the job that runs at T0 + 30 days (§5).

| # | Class | Contents | Purpose | Store | Retention (default, pending human sign-off) | Rationale | Erasure step |
|---|---|---|---|---|---|---|---|
| D01 | Account | `users`: id (UUIDv7), username, argon2id hash, status, created_at, deleted_at | authentication; ownership | Postgres | Life of account, then 30-day grace | ADR-0002/0005. Grace protects against mistaken or stolen-token deletion (TM-31). | Soft delete at T0. Row deleted at hard delete. FKs from erased decision stubs set to NULL. |
| D02 | Sessions | `sessions`: token_hash (sha256), user_id, expires_at, revoked_at | authentication | Postgres | Token TTL 24 h (2 h on a public demo). Expired or revoked rows purged 7 days after expiry. | ADR-0005 TTL. 7 days is a short abuse-investigation window. | All revoked at T0. Rows deleted at hard delete. |
| D03 | Login rate-limit counters | per-username and per-IP counters | brute-force defence | API memory only | 15-minute window (ADR-0005). Never persisted. | IPs are personal data and are not needed beyond the window. | Expire naturally. Nothing to erase. |
| D04 | Consent records | consent_version, text_sha256, scopes, granted_at, revoked_at, source, synthetic (§4) | lawful basis and evidence | Postgres | Life of account | Evidence of what was agreed to | Deleted at hard delete. The erasure ledger (D17) keeps no consent content. |
| D05 | Preferences | board, angle, grade band, goals, privacy settings | personalization of candidates | Postgres | Life of account | Needed for the service | Deleted at hard delete. |
| D06 | Attempts | attempts, `attempt_revisions` audit, idempotency key and fingerprint, status (incl. RETRACTED) | history, features, corrections | Postgres | Life of account. Retracted attempts are kept for audit until erasure. | History is the product. Revisions are required by ADR-0002. | Deleted at hard delete. |
| D07 | Notes | free text, 500 chars or fewer, inside attempts/revisions | owner's own memo | Postgres only | Life of the attempt | Owner value only. Never used by models or ranking. | Deleted with D06. Web drafts are cleared on logout/delete (D16). |
| D08 | Outbox | `attempt_event_outbox` payloads containing user_id | at-least-once publication | Postgres | Published rows purged 7 days after `published_at` | Matches `attempts.v1` retention and the reconciliation window. ADR-0002 says "age out" but assigns no job (FU-4). | The publisher skips the user's unpublished rows from T0. All of the user's rows are deleted at hard delete. |
| D09 | Feature snapshots | `user_feature_snapshots` (aggregates, checksum) | ranking input, replay | Postgres | Latest per (user, schema version), plus those referenced by a retained decision. Others purged after 30 days. | Recomputable from attempts (BC-0113). | Deleted at hard delete. Recomputed if an operator restores during grace. |
| D10 | Decisions and exposures | `recommendation_decisions` (user_id, session_id, constraints, rng_seed, ranked results, explanation), `recommendation_exposures` | replay, explanation, experiment analysis | Postgres | Life of account | Replay is a product feature (PRD §9) | At hard delete: exposures deleted. Decision reduced to an **erased stub** (decision_id, requested_at truncated to date, policy/model version, experiment_id, served_variant, `erased=true`). user_id, session_id, rng_seed (a hash of user_id), constraints, results and explanation removed. Replay returns `REPLAY_UNAVAILABLE_ERASED`. Needs contract change C-03. |
| D11 | Candidate snapshots (replay artifacts) | `data/decisions/<sha256>.json` with `user_features` and per-candidate scores | faithful replay (ADR-0003) | local FS (gitignored `data/`) | Life of the referencing decision | ADR-0003 | Files referenced by the user's decisions deleted at hard delete. Committed fixtures (`test/replay/fixtures`, `contracts/decisions/examples`) are synthetic only and kept indefinitely. |
| D12 | Kafka events | `attempts.v1` (user_id key and attempt attributes), `climber-features.v1` (per-user aggregates, `recent_variant_ids`), `stream-dlq.v1` (original event bytes) | streaming features | Kafka | `attempts.v1`: 7 days (ADR-0002) with `segment.ms` of 1 day or less. `climber-features.v1`: compacted, `max.compaction.lag.ms` 7 days or less, `segment.ms` 1 day or less, `delete.retention.ms` 1 day. `stream-dlq.v1`: **14 days** (ADR-0002 says 30; amend). | §5.3 aging argument | §5.3: tombstone on the features topic at T0. Topics with time retention age out. The DLQ is shortened so it ages out before hard delete completes. |
| D13 | Flink state | per-user keyed state, retained checkpoints, savepoints | event-time aggregation | Flink state backend / local volume | Keep the last 2 retained checkpoints. Savepoints deleted after 30 days. | Bounded recovery window | User key state cleared at T0 by the privacy control event (GAP-4, no ticket yet). After restoring any savepoint, re-apply the erasure ledger. |
| D14 | Application logs | JSON lines: request_id, pseudonymous user_id, route, status, latency | operations | container stdout, Docker `json-file` | 14 days, with rotation (`max-size`/`max-file`; FU-2) | Long enough to debug one sprint; shorter than the grace period | Age out before hard delete completes (14 days < 30 days). No scrubbing job. |
| D15 | Traces and metrics | OTel spans (request_id, event_id attributes), Prometheus series (no user labels) | observability (BC-0308) | Tempo, Prometheus | Traces 7 days. Metrics 15 days (Prometheus upstream default; verify at BC-0308). | Incident window | Traces age out. Metrics hold no user identifiers. |
| D16 | Client-side storage | `sessionStorage` token (BC-0115), IndexedDB attempt drafts incl. notes (BC-0118) | session; offline drafts | user's browser | Token: tab lifetime. Drafts: until synced, logout or delete. | Shared-device risk (TM-32) | Web clears both on logout and on a successful `DELETE /v1/me`. |
| D17 | Erasure ledger | HMAC-SHA256(user_id, server key), requested_at, hard_deleted_at, per-store checklist results | prove erasure; re-apply after restore | Postgres | 90 days after hard delete | Covers savepoint and backup windows (30 days) with margin | Rows purged after 90 days. Holds no username, notes or attempt data. |
| D18 | Model artifacts | Model A (route-only, trained on the licensed catalog), Model B (if Gate D1 = GO, trained on attempt histories), manifests | ranking | `data/models/<version>/` (gitignored), `model_versions` | Model A: until superseded plus 90 days for rollback. Model B: same, subject to §6. | Rollback window (PRD §9.2) | §6 exception policy. Model A has no personal data. Its publication is governed by D1 (licence), not privacy. |
| D19 | Training/eval datasets and outputs | local extracts with per-user rows (`data/`, gitignored), reports in `reports/` (gitignored), committed `docs/eval-reports/*` | ML evaluation | local FS; git (aggregate only) | Local per-user extracts: deleted when the run is archived, or at hard delete if sooner. Committed reports: aggregate only, kept indefinitely. | PRD §6.4: pseudonymize IDs and enforce deletion of exported personal data | The hard-delete job deletes the user's rows from registered local extract paths. Reports with per-user rows must not be committed. |
| D20 | Benchmarks | load-test outputs (BC-0601, BC-0607) | performance evidence | `reports/`, `docs/benchmarks/` | Indefinite | Synthetic accounts and events only (PRD §10), so no personal data | None needed. A benchmark containing a real user ID is a defect. |
| D21 | Founder's raw BoardLib logbook export | the founder's own CSV (BC-0501) | Gate D1 feasibility | `data/raw/` (local only) | Until the founder deletes it | Consent is the founder's own. Outside the app's erasure path. | Manual deletion by the founder. The app erasure job does not touch `data/raw`. Documented in `docs/runbooks/erasure.md` (BC-0412). |
| D22 | Backups / DB dumps | none in MVP | n/a | n/a | **No backups by default.** If enabled: 30 days or less. | Avoids an unbounded erasure surface | If enabled, every restore re-applies the erasure ledger (D17) before the service starts. |
| D23 | Licensed catalog | Kilter routes, geometry (not personal) | catalog | `data/raw`, `data/catalog` (local only) | Governed by `docs/data-source-audit.md` (BC-0002) | Licence, not privacy | n/a. Never exported, published or put on any demo host (D1). |

**E01 data-class coverage check** (BC-0007 acceptance: "no E01 ticket introduces a data class not listed"):

| E01 task | Data classes it introduces |
|---|---|
| BC-0101 | D01, D23, provenance (not personal) |
| BC-0107 | D02, D03, D04 |
| BC-0108 | D05 |
| BC-0109 | D06, D07, D08 |
| BC-0111 | D10, D11 |
| BC-0113 | D09 |
| BC-0115 | D16 (token) |
| BC-0118 | D16 (drafts) |
| BC-0120 | synthetic seed accounts (D01 with `synthetic=true`) |
| BC-0122 | D14, D03 |

BC-0102–0106, 0110, 0112, 0114, 0116, 0117, 0119, 0121 and 0123–0125 add no new personal data class. The integrator must re-run this check whenever an E01 ticket's schema changes.

## 3. Lawful basis and purpose limits

- **Service purpose** (consent scope `service`) covers D01–D16. Attempt data is used only for this user's features, ranking, replay and export.
- **Research purpose** (consent scope `research`, optional, **default off**) is required before any user's data, other than the founder's own export (BC-0501), enters training or eval datasets (D19) or a prospective pilot (BC-0511). The contract does not carry scopes yet (C-07). Until it does, no non-founder data is used for research.
- Restricted research access (PRD §10): per-user extracts are produced only on the developer laptop, are never committed, and are never copied to a hosted machine.

## 4. Consent versioning

### 4.1 Consent record fields
`consent_id` (UUIDv7), `user_id`, `consent_version` (`MAJOR.MINOR`, e.g. `1.0`), `text_sha256` (hash of the exact text shown), `scopes` (`["service"]`, optionally `"research"`), `granted_at`, `revoked_at` (nullable), `source` (`web-onboarding` | `seed-synthetic`), `synthetic` (bool).
The contract currently has `version`, `consented_at` and `revoked_at`. `scopes` and `text_sha256` need C-07. Consent texts are versioned files (proposed path `docs/consent/consent-v<MAJOR.MINOR>.md`, owned with BC-0115). The server accepts only versions listed in its configuration, and an unknown version returns 422.

### 4.2 Version bump rules
- **MAJOR**: a new data class, a new purpose or scope, a new recipient or host (including any public demo), longer retention, or any research use. Users must re-consent before the next attempt or recommendation write. Until then the API returns 403 `CONSENT_REQUIRED`. Data collected earlier stays under the version it was collected under.
- **MINOR**: wording clarification, shorter retention, or a narrower purpose. No re-consent. Users are notified at next login.
- Any change to this document that touches §2 retention or §3 purposes requires deciding which bump applies, and recording that decision in the change.

### 4.3 What withdrawal (`DELETE /v1/me/consent`) triggers
1. Immediately, attempt POST/PATCH and recommendation POST return 403 `CONSENT_REQUIRED` (BC-0107; both operations declare 403 in the contract).
2. The user is excluded from experiment assignment, and from any training/eval export from that moment (BC-0501 importer filter).
3. Existing data is **retained** and remains readable and exportable (access right) until the user requests erasure. The UI offers erasure at the point of withdrawal.
4. Withdrawing only `research` scope (once C-07 lands): no new research use. Existing artifacts fall under §6.
5. No event is emitted. Features stop changing because no new attempts arrive.

## 5. Erasure procedure

### 5.1 Semantics
`DELETE /v1/me` is asynchronous and idempotent. It requires step-up password re-authentication (C-06, TM-31). It returns an erasure status, and repeat calls return the same status. During the 30-day grace the account is disabled. Only an operator, at the user's password-verified request, can restore it, following `docs/runbooks/erasure.md` (BC-0412). This is an **erasure, not anonymization**, policy: with fewer than about 3 real users, de-identified remnants can be re-linked to the person.

### 5.2 Checklist (BC-0412 implements; numbering is normative)

**At T0, soft delete (one transaction where possible):**
1. `users.status = ERASURE_PENDING`, `deleted_at = T0`. Insert an erasure-ledger row (D17).
2. Revoke all sessions (`sessions.revoked_at = T0`). Block login.
3. Stop processing: the outbox publisher skips the user's unpublished rows. Exclude the user from experiment assignment and from training/eval exports. Redrive refuses the user's DLQ records.
4. Emit the privacy control event (GAP-4). Flink clears the user's keyed state.
5. Produce a tombstone (key = user_id, value = null) to `climber-features.v1`.
6. If Redis exists: `DEL` the user's keys.
7. Web client: clear `sessionStorage` and IndexedDB drafts on the success response.

**At T0 + 30 days, hard delete job (idempotent and resumable; records each step in the ledger):**
8. Postgres: delete the user's rows from `attempts`, `attempt_revisions`, `attempt_event_outbox`, `user_feature_snapshots`, `recommendation_exposures`, `user_preferences`, consent records, `sessions`, reconciliation rows (BC-0309), experiment allocation/exposure rows (BC-0406), skill-memory rows (BC-0507). Reduce `recommendation_decisions` to erased stubs (D10). Delete the `users` row.
9. Files: delete `data/decisions/**` snapshots referenced by the user's decisions, any cached export file, and the user's rows in registered local extract paths (D19).
10. Kafka: no record-level action. Assert topic configurations (§5.3) and that the step 5 tombstone was written.
11. Flink: assert that no keyed state exists for the user. Delete or flag savepoints older than T0 (D13).
12. Logs and traces: no action. Assert the retention configuration is at or below the D14/D15 values.
13. Model artifacts: apply §6 and record the outcome in the ledger.
14. Backups (if ever enabled): record that a restore must re-apply the ledger.
15. Set `hard_deleted_at`. Replay of any of the user's decision IDs now returns `REPLAY_UNAVAILABLE_ERASED` (ADR-0003). Other users still get 404, never a different code, so the response does not reveal whether an ID belonged to an erased user.

### 5.3 Kafka aging argument
- **`attempts.v1` (time retention 7 days).** After T0 no new events are produced for the user (step 3), so every record carries a timestamp of T0 + ε or earlier, where ε is the publisher lag. Kafka deletes whole segments once a segment's newest record is older than `retention.ms`, so a record can survive up to about `retention.ms + segment.ms` (upstream default `segment.ms` is 7 days; verify at BC-0301). With `segment.ms` of 1 day or less, every record is gone by about T0 + ε + 8 days, before hard delete at T0 + 30 days. Use `message.timestamp.type = LogAppendTime`, so a back-dated client `occurred_at` cannot alter retention (FU-4).
- **`stream-dlq.v1`.** A DLQ record can be created while the source record is still readable (up to about T0 + 8 days), and redrive resets its clock. With ADR-0002's **30-day** retention it can survive to about T0 + 39 days, **past** the grace period, so the argument fails. Policy: DLQ retention **14 days** (default, pending; amend ADR-0002) with `segment.ms` of 1 day or less, giving about T0 + 23 days, and redrive refuses erased users (step 3). DLQ records must never echo notes (`error_message`), see TM-23.
- **`climber-features.v1` (compacted).** Compaction keeps the latest value per key forever, so this topic **does not age out**. It needs the explicit tombstone from step 5, issued at **T0** rather than at hard delete, because compaction is lazy. With `max.compaction.lag.ms` of 7 days or less and `segment.ms` of 1 day or less, earlier values are removed by about T0 + 8 days, and the tombstone itself by `delete.retention.ms` (1 day) after that. Features are derived, so an operator restore during grace recomputes them (BC-0113).
- **Flink state and checkpoints** do not age out with topic retention, because 90-day aggregates hold state far longer. They need step 4 and the D13 savepoint rule.

### 5.4 What the BC-0412 erasure test must enumerate
1. **Every Postgres table discovered at test time** from `information_schema`, including any column named `user_id` or any FK path to `users`, plus `attempt_event_outbox.payload_json ->> 'user_id'`. The test fails if a discovered table is not in a classification registry. This catches new tables added after this policy.
2. Every file under `data/decisions/` referenced by the user's decisions before deletion, and a scan of `data/decisions/` for the user's UUID string.
3. Registered local extract paths (D19) and any export cache.
4. Kafka topic configs for all three topics (`retention.ms`, `segment.ms`, `cleanup.policy`, `max.compaction.lag.ms`, `delete.retention.ms`, `message.timestamp.type`) against §2 D12, plus the presence of the features tombstone.
5. Flink keyed state for the user (once E03 exists, via a state-processor test or a job test harness).
6. Sessions revoked and login blocked during grace. The replay error code after hard delete. An unchanged 404 for other users.
7. Erasure stubs contain none of: user_id, session_id, rng_seed, request_constraints, ranked_results, explanation.
8. A second run of the job is a no-op (idempotent).

## 6. Model-artifact exception policy

**Default:** artifacts trained before an erasure are **kept** until the next scheduled retrain, **unless** they contain or memorize identifiable rows. Retrain cadence for Model B, if Gate D1 = GO: at the next scheduled training run, or within **90 days** of any erasure that touches its training set, whichever is sooner (default, pending).

**Decision procedure** (performed at hard-delete step 13 and recorded in the ledger):
1. Did the artifact's training manifest include the user? Manifests store HMAC'd user and attempt IDs, so this can be checked without keeping raw IDs. If no, keep the artifact.
2. Does the artifact have **user-indexed parameters**, such as per-user embeddings, user-ID features or per-user calibration? If yes, delete the user's parameters if the format allows it. Otherwise retire the artifact and retrain **within the grace period**.
3. Is it **instance-based or does it store training data**, such as kNN, stored training frames, or pickled DataFrames? This is forbidden by TM-14/TM-15 for new artifacts. If found, retire it and retrain within grace.
4. Is it a **small-population artifact**? Default thresholds, pending: the erased user's rows are more than 10% of training rows, or the training set has fewer than 5 distinct users. If yes, treat it as memorizing and retire/retrain within grace. Under the Gate D1 NO-GO default, any Model B trained on the founder's history is a single-user model, so it is retired if the founder erases.
5. Otherwise, keep it until the scheduled retrain. Mark the model card (BC-0506) `TRAINING_DATA_PARTIALLY_ERASED`. Exact reproduction of that model from its manifest is no longer possible, the training-side equivalent of `REPLAY_UNAVAILABLE_ERASED`.

Model A is trained on the licensed route catalog and holds no user data, so it is unaffected by erasure. Its **publication** is blocked by D1/Gate D0 without written human sign-off.

## 7. Demo-mode rules

1. **Synthetic accounts only** on any demo. Seeded accounts have `synthetic=true` (D04 `source=seed-synthetic`).
2. **No real data on any public host. Ever.** This includes the founder's logbook, the Kilter catalog (D1) and anything derived from them. No public host exists until the human fills ADR-0007 "Approved public demo host".
3. On a public host (GAP-3), the API refuses to start in `DEMO_MODE=public` if any non-synthetic account exists. Real-account creation is disabled.
4. **A demo banner on every page** reads "Synthetic demo data. Do not enter personal information." The notes field is hidden and the API rejects `notes` in public mode.
5. **Demo token lifetime: 2 h** (default, pending; local dev keeps 24 h per ADR-0005).
6. Per-visitor ephemeral synthetic accounts, so one visitor never sees another's history and per-username lockout cannot block other visitors (TM-22).
7. No `ADMIN` account on a public host. Secrets are random per deploy and never committed. Seed scripts never print a fixed password.
8. The database resets every 24 h (default, pending). TLS-terminating proxy. Proxy access logs truncate IPs where configurable and are kept 7 days or less (default, pending).
9. Local laptop demo and recorded video (BC-0606): synthetic accounts only. No real history appears in screenshots, recordings or docs.

## 8. Logging rules

1. Identify users by **pseudonymous `user_id` (UUID) only**. Never log username, password, token, token hash, `Authorization`/`Cookie` headers, DB DSN, or IP address (application logs; proxy logs follow §7.8).
2. **Never log `notes`**, and **never log raw request or response bodies** for `/v1/auth/**`, `/v1/me/attempts**`, `/v1/me/consent`, `/v1/me/export` or `DELETE /v1/me`. Log field names and validation codes only.
3. Always include `request_id`. Include `event_id`/`attempt_id` where useful. `Idempotency-Key` is logged only after the charset restriction (C-02), and truncated to 64 characters.
4. Error envelopes carry code, message and `request_id`, never stack traces (BC-0122). Messages never echo submitted values. DLQ `error_message` follows the same rule.
5. OTel: no header or body capture. No `user_id` metric labels.
6. Retention: application logs **14 days** with rotation, traces 7 days, metrics 15 days (§2 D14/D15; defaults, pending).
7. CI logs must never print `.env` contents. Agent handoffs must never include env dumps or credentials (GAP-2).

## 9. Data-subject access and export

### 9.1 `GET /v1/me/export` (owner, authenticated, step-up per C-06, `Cache-Control: no-store`, rate-limited)
JSON containing:
- profile (user_id, username, created_at, status) and full consent history
- preferences
- every attempt with all revisions, **including the owner's notes**, idempotency keys, status
- latest feature snapshot
- decisions: request constraints, ranked results, reason codes, versions, `feature_as_of`, the `user_features` used, and experiment variant served
- exposures
- skill-memory estimates (BC-0507) once they exist.

Excluded: password hash, session token hashes, request fingerprints, other users' data, internal snapshot URIs and storage paths, policy code digests, and **licensed route geometry** (routes are referenced by `route_variant_id` only, D1).

### 9.2 Public-mode exports
A public-mode export is any dataset or artifact that leaves the owner: benchmarks, demo fixtures, eval artifacts, screenshots, published reports. Such exports contain **synthetic user IDs only**, and **no notes**, usernames, free text, real attempt rows, consent records or exact timestamps tied to a real person. Real users' data appears in a public-mode export only as aggregates, with explicit `research` consent and human approval (PRD §10 "For public benchmarks, use synthetic user IDs").

## 10. Cross-reference: policy item to implementing ticket and test

Ticket references verified against `planning/01-backlog.csv`. Test references use the exact first-column text of `planning/04-test-and-evaluation-matrix.md` §1 or §3, or are marked GAP (see threat model §4.1, proposed SEC IDs).

What the named tickets are:
- **BC-0107**: Auth and identity: local accounts, session tokens, consent, GET /v1/me
- **BC-0122**: Error envelope, request IDs, structured logging, rate limiting
- **BC-0124**: Test audit of the vertical slice (read-only). It verifies with mutation checks; it does not implement.
- **BC-0412**: Privacy: GET /v1/me/export, DELETE /v1/me, retention job, erased replay semantics
- **BC-0109**: Attempt logging (idempotent POST, revisioned PATCH, outbox)
- **BC-0115**: Web onboarding/consent
- **BC-0118**: Web attempt log with offline drafts
- **BC-0120**: Compose dev stack and seed-demo
- **BC-0301**: Outbox publisher to Kafka
- **BC-0302**: Event contract tests, DLQ and redrive
- **BC-0308**: Observability
- **BC-0401**: Decision record v2 freeze incl. erasure semantics
- **BC-0402**: Faithful replay engine
- **BC-0413**: Security/privacy review of replay and erasure (read-only)
- **BC-0501**: Real attempt-history inventory
- **BC-0506**: Model B model card
- **BC-0603**: Security hardening/final review
- **BC-0606**: Recorded demo and public README

| ref | policy item | implementing ticket | test |
|---|---|---|---|
| PR-01 | No health/biometric/location/minors' data; perceived_effort never used for medical inference | BC-0107, BC-0109, BC-0124 | GAP → see threat model §4.1 |
| PR-02 | notes 500 chars or fewer, rejected above | BC-0109 | §3:Oversized notes / injection |
| PR-03 | notes never logged | BC-0122, BC-0107 | §3:Oversized notes / injection; §3:Secrets in logs |
| PR-04 | notes excluded from events (allowlist, C-01) | BC-0109, BC-0302 | GAP → see threat model §4.1 |
| PR-05 | Consent record, version validation, 403 CONSENT_REQUIRED after withdrawal | BC-0107, BC-0115 | §1:FR-03 profile + consent |
| PR-06 | Withdrawn or non-research users excluded from training/eval exports | BC-0501 | GAP → see threat model §4.1 |
| PR-07 | Logging rules (pseudonymous user_id, no tokens or bodies, request_id) | BC-0122, BC-0107, BC-0308 | §3:Secrets in logs |
| PR-08 | Session TTL and revocation on logout/erasure | BC-0107, BC-0412 | §3:Forged/expired token |
| PR-09 | DELETE /v1/me soft delete, 30-day grace, hard delete checklist §5.2 | BC-0412 | §1:US-08 export/delete |
| PR-10 | Erasure test enumerates stores per §5.4 | BC-0412, BC-0413 | §1:US-08 export/delete |
| PR-11 | Erased replay returns REPLAY_UNAVAILABLE_ERASED; erased stub contents (C-03) | BC-0401, BC-0402, BC-0412 | §1:FR-10 replay |
| PR-12 | Kafka topic configs (segment.ms, LogAppendTime, DLQ 14 d) and outbox purge | BC-0301, BC-0302 | GAP → see threat model §4.1 |
| PR-13 | Features tombstone and Flink state clear at T0 | GAP → see threat model §4.1 | GAP → see threat model §4.1 |
| PR-14 | Export content and exclusions §9.1 | BC-0412 | §1:US-08 export/delete |
| PR-15 | Public-mode exports synthetic only §9.2 | BC-0601, BC-0606, BC-0603 | §3:Unlicensed data |
| PR-16 | Model-artifact exception policy §6 | BC-0506, BC-0205 | GAP → see threat model §4.1 |
| PR-17 | Demo-mode rules §7 | BC-0120, GAP → see threat model §4.1 | GAP → see threat model §4.1 |
| PR-18 | Client storage cleared on logout/delete | BC-0115, BC-0118 | GAP → see threat model §4.1 |
| PR-19 | Log/trace retention and Docker log rotation | BC-0308, BC-0120 | GAP → see threat model §4.1 |
| PR-20 | Negative-test audit of the policy items in the vertical slice | BC-0124 | §3:Cross-user history read / IDOR |

### 10.1 Verification command and output

```bash
python3 -I - docs/privacy-and-retention.md <<'PY'
import csv, re, sys
ids = {r['id'] for r in csv.DictReader(open('planning/01-backlog.csv', newline=''))}
m = open('planning/04-test-and-evaluation-matrix.md').read()
def labels(sec, nxt):
    body = m.split('\n## %s.' % sec)[1].split('\n## %s.' % nxt)[0]
    return {l.split('|')[1].strip() for l in body.splitlines()
            if l.startswith('|') and not l.startswith('|---')}
L = {'1': labels('1', '2'), '3': labels('3', '4')}
doc = open(sys.argv[1]).read()
rows = [l for l in doc.splitlines() if l.startswith('| PR-')]
GAP = 'GAP → see threat model'
bad = []
for l in rows:
    c = [x.strip() for x in l.strip().strip('|').split('|')]
    task, test = c[2], c[3]
    bcs = re.findall(r'BC-\d{4}', task)
    refs = re.findall(r'§([13]):([^;]+)', test)
    badref = [r for r in refs if r[1].strip() not in L[r[0]]]
    if len(c) != 4 or not (bcs or GAP in task) or [b for b in bcs if b not in ids] \
       or not (refs or GAP in test) or badref:
        bad.append((c[0], badref))
alld = set(re.findall(r'BC-\d{4}', doc))
print('PR rows:', len(rows), '| distinct BC IDs in doc:', len(alld),
      '| missing from backlog:', sorted(i for i in alld if i not in ids) or 'none')
print('problems:', bad or 'none')
for i in ['BC-0107', 'BC-0122', 'BC-0124', 'BC-0412']:
    print(i, '=', next(r['title'] for r in csv.DictReader(open('planning/01-backlog.csv', newline='')) if r['id'] == i))
PY
```

```text
PR rows: 20 | distinct BC IDs in doc: 32 | missing from backlog: none
problems: none
BC-0107 = Auth and identity: local accounts, session tokens, consent, GET /v1/me
BC-0122 = Error envelope, request ids, structured logging, rate limiting
BC-0124 = Test audit of the vertical slice (read-only)
BC-0412 = Privacy: GET /v1/me/export, DELETE /v1/me, retention job, erased replay semantics
```
