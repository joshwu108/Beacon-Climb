<!-- Saved by the top-level session from the read-only security-reviewer subagent output, 2026-10-15. Reviewed tree: main at the BC-0120 commit plus the uncommitted BC-0121 test files (not reviewed). -->

# BC-0123 security review: Beacon Climb vertical slice (main @ 0028299)

**Verdict for G1 (security side): not ready until F1 has an owner ticket.** No cross-user data access, IDOR, SQL injection, token or notes leak, or licensed-data leak turned up. One HIGH denial-of-service issue and five MEDIUM issues need tickets. F1 and F2 should be fixed before the slice is tagged, or the risk accepted in writing on the grounds that the stack only listens on 127.0.0.1.

**How the review was done:**
- Read-only. I modified no repo files and ran no git add or commit.
- I did not start the compose stack. Two findings were reproduced with small throwaway programs in the session scratchpad, using the exact jar versions the app resolves (Tomcat 11.0.24, Spring 7.0.9, spring-security-crypto 7.1.1, bcprov 1.86).
- Repro commands that need the running stack are given but were not run.
- Another agent has uncommitted changes in the tree (`Makefile`, `docs/runbooks/dev-stack.md`, `.github/workflows/e2e.yml`, `test/e2e/`). I did not review them.

## Findings

### F1 HIGH: anyone can crash login and registration by sending about 12 requests at once (verified)
- **Files:**
  - `services/api/src/main/java/com/beaconclimb/api/identity/PasswordHasher.java:26-35`: every password hash uses 64 MiB of Java memory.
  - `identity/AuthService.java:59-63`: `register` hashes inside `@Transactional`, so it also holds a database connection for the whole hash.
  - `identity/AuthService.java:83-94`: login hashes every time.
  - `platform/web/RateLimitBuckets.java:71`: the bucket starts full, so the first 30 requests are allowed all at once.
  - `application.yaml:34`: the public limit is 30 per IP.
  - `infra/compose/docker-compose.yml:73,110`: a 1 GB container and `MaxRAMPercentage=75` give about 768 MiB of heap.
- **Verified:** a scratch program ran the same password encoder settings on parallel threads with `-Xmx768m`.
  - 8 threads: all succeeded.
  - 12 threads: 2 `OutOfMemoryError`.
  - 30 threads: 19 `OutOfMemoryError`.
  - The real app has less spare heap than this test, so it will fail sooner.
- **Repro (not run against the stack):**
  ```
  seq 30 | xargs -P30 -I{} curl -s -o /dev/null -w '%{http_code}\n' -X POST http://127.0.0.1:8080/v1/auth/register -H 'Content-Type: application/json' -d '{"username":"dos_{}x","password":"password-{}"}'
  ```
  Expect `500 INTERNAL_ERROR` responses and `OutOfMemoryError: Java heap space` in the api logs. Concurrent registrations can also use up the database connection pool, which is 10 by default.
- **Smallest fix:**
  - Cap concurrent hashes in `PasswordHasher` with a fair semaphore (permits roughly heap budget / 64 MiB, say 2 to 4). Use a short `tryAcquire` and answer 503 or 429 with `Retry-After` when it fails.
  - Hash before the transaction in `register`.
  - Give `/v1/auth/*` its own bucket with a small burst (for example 5, refilling to 30 per minute).
- **Test:** an integration test that fires 40 parallel `register` and `session` calls and asserts only 201, 401, 429 or 503 come back, never 500. Add a unit test that the hasher never runs more than N hashes at once.

### F2 MEDIUM: a disguised URL path skips the rate limit on public endpoints (verified in a replica harness)
- **Files:**
  - `identity/BearerAuthenticationFilter.java:61-69`
  - `platform/web/WriteRateLimitFilter.java:50-53`
- **What happens:** both filters check the raw path with `getRequestURI().startsWith("/v1/")`. Spring routes on the decoded path with `;params` removed, so the two disagree.
- **Verified:** a scratch Tomcat + DispatcherServlet using the same `startsWith` check gave:
  - `GET /%761/me` → filter skipped, `/v1/me` handler ran (HTTP 200).
  - `GET /v1;a=b/me` → filter skipped, handler ran (HTTP 200).
  - `/v1/m%65` → caught by the filter (401).
- **Impact:**
  - `POST /v1;x/auth/register`, `/%761/auth/register` and `/%761/auth/session` skip the 30-per-minute public limit. Registration has no other limit, which removes the main brake on F1 and on username probing through the 409 `USERNAME_TAKEN` response.
  - `/%761/...` also works through nginx, because `location /v1/` matches the decoded path and `proxy_pass $api` forwards the raw one.
  - Login itself is not bypassed. Every protected handler declares `AuthenticatedUser`, and the argument resolver returns 401 when it is missing (`identity/IdentityWebConfig.java:40-45`). Any future handler that forgets that parameter would be open, though.
- **Repro:** send `curl -X POST 'http://127.0.0.1:8080/v1;x=1/auth/register' ...` 31 times within a minute. All 31 return 201 and none carries `X-RateLimit-Remaining`.
- **Smallest fix:** run one early filter that rejects any path containing `;`, `%2e`, `%2f` or `%5c`, or any path whose decoded form differs from the raw form (404 envelope). Alternatively, match on Spring's parsed path (`ServletRequestPathUtils.parseAndCache`) in both filters.
- **Test:** an integration test where `/%761/me` without a token gets the filter's 401, and 31 posts to `/v1;x/auth/register` produce a 429 (or are all refused).

### F3 MEDIUM: behind nginx, one person's failed logins lock everyone out
- **Files:**
  - `apps/web/nginx/default.conf:32` uses `$proxy_add_x_forwarded_for`, which appends to the client's header.
  - `platform/web/ClientAddress.java:18-27` uses the socket address because `trust-forwarded-headers` is false.
  - `identity/LoginAttemptLimiter.java:44-48` and `AuthService.java:85-91` check the per-IP bucket before checking the password.
- **What happens:** every browser request arrives from nginx's container IP. After 10 failed logins through :3000, every login through :3000 gets 429 for 15 minutes, including correct ones.
  - The runbook (`docs/runbooks/dev-stack.md:57-60`) accepts this for write limits but does not mention the login lockout.
  - `application.yaml:37-38` says nginx must set the header from `$remote_addr`, but it appends instead. Turning on `trust-forwarded-headers` would therefore let clients spoof their IP, because `ClientAddress` takes the first entry.
- **Repro:** 10 bad logins via `http://127.0.0.1:3000/v1/auth/session`, then `demo`/`demo-password` via :3000 → 429. The same login via :8080 → 201.
- **Smallest fix:** in nginx, `proxy_set_header X-Forwarded-For $remote_addr;`. In the api, trust XFF only from a configured proxy address range (new `beacon.http.trusted-proxies`).
- **Test:** `ClientAddressTest` cases for trusted and untrusted peers, plus an integration test with two simulated clients behind the proxy.

### F4 MEDIUM: on a shared browser, unsent attempt drafts (with notes) carry over to the next account and get submitted under it
- **Files:**
  - `apps/web/src/features/attempts/draft-store.ts:26-37`: drafts store no owner.
  - `attempt-sync.ts:83-95,113-122`: failed drafts are re-sent automatically on page load with whatever token is current.
  - `features/onboarding/OnboardingPage.tsx:69-79`: a 401 clears the token but not the drafts.
  - `features/auth/useAuthenticate.ts:30-33`: signing in does not filter or clear drafts.
  - Drafts are only cleared by the Sign out button (`sign-out.ts`). IndexedDB survives closing the tab; sessionStorage does not.
- **Repro (Playwright):**
  1. Sign in as A. Go offline with `context.setOffline(true)`.
  2. Log an attempt with notes "A-private". Close the page without signing out.
  3. Open a new page in the same browser context, register B, and go online.
  4. Open `/attempts`. A's draft is shown and then POSTed with B's token, so B's history now contains A's notes.
- **Smallest fix:** store `owner_user_id` (from `GET /v1/me`) on each draft. List and sync only the current user's drafts. Purge other users' drafts after sign-in and on 401.
- **Test:** unit tests for draft-store and sync filtering, plus an extension of the SEC-25 Playwright test with the scenario above.

### F5 MEDIUM: one user can fill the global snapshot limit and switch off recommendations for everyone
- **Files:**
  - `recommendations/LocalFsSnapshotStore.java:57-58` and `application.yaml:49`: one store-wide cap of 100,000 files.
  - `RecommendationService.java:241-247`: 503 once the cap is reached.
- **What happens:** every POST whose snapshot content differs writes a new file. Changing one entry in `exclude_route_variant_ids` is enough to make it differ. At 60 per minute, one user fills the store in about 28 hours; several accounts, or registration through F2, do it much faster. After that, every user gets 503 and the files use several GB of disk.
- **Smallest fix:** a per-user daily cap on new decisions, counted in SQL on `recommendation_decisions (user_id, created_at)`, plus an alert at 80% of the store cap.
- **Test:** an integration test with `max-files=5`. When user A hits their cap, user B still gets 200.

### F6 LOW: the api process has the schema-owner password, and some audit tables are writable
- `docker-compose.yml:78-79` and `application.yaml:13-14`: the long-running api has `BEACON_MIGRATE_DB_PASSWORD` (the role that owns the tables) so it can run Flyway. Anyone who takes over the api process can undo the V6 ledger `REVOKE` (TM-24).
- The app role can also UPDATE and DELETE rows in `attempt_revisions`, which is supposed to be append-only (ADR-0002), and in `ranking_policies` and `flyway_schema_history`, through the default privileges in `infra/compose/postgres/init.sql:29-30`.
- **Fix:**
  - Run migrations in a one-shot `migrate` service, and give the api `SPRING_FLYWAY_ENABLED=false` with no migrate password.
  - `REVOKE UPDATE, DELETE ON attempt_revisions FROM beacon_app` in a new migration.
- **Test:** `docker compose config` shows no migrate password on the api service, and `MigrationIT` asserts the new privileges.

### F7 LOW: the licensed-data guard still misses some file types (verified)
- `scripts/ci/check-no-licensed-data.sh:15-18,49-62` decides by file extension only. A scratch run on `kilter.bin` (with a SQLite file header), `routes.json`, `weights.safetensors`, `model.onnx`, `model.pkl` and `cat.jsonl.gz` passed with exit 0.
- **Fix:** add those extensions plus `.npz`, `.npy`, `.h5`, `.zip`, `.gz`; also detect the SQLite file header on every tracked file.
- **Test:** add these cases to `test/guards/check-no-licensed-data.test.sh`.

### F8 LOW: known vulnerabilities in dev-only packages (verified with `pnpm audit`)
- Root `pnpm audit`: 3 critical and 2 moderate, all under `apps__web>vitest@3.2.4`.
  - vitest: GHSA-5xrq-8626-4rwp (critical, only exploitable while the Vitest UI or API server is listening; the scripts only use `vitest run`) and GHSA-82fw-gwwq-j7x9 (moderate, also in `@vitest/mocker`).
  - tinypool 1.1.1: GHSA-5gmw-xhrv-c9v3 and GHSA-85c8-ppgw-ccpr (critical prototype-pollution gadgets).
- `pnpm audit --prod` (root and `apps/web`): 0 vulnerabilities.
- Gradle runtime set (Boot 4.1.1, Spring 7.0.9, Tomcat 11.0.24, pgjdbc 42.7.13, bcprov 1.86, logback 1.5.38, Jackson 3, json-schema-validator 3.0.8, bucket4j 8.21.0): no CVEs I know of. This is not a scan.
- `./gradlew dependencyCheckAnalyze` does not exist (no plugin is configured). `pip-audit` is not installed, so `services/ml` was not audited.
- **Fix:** upgrade to vitest ≥4.1.11 (or override tinypool ≥2.1.2), and add `pnpm audit --audit-level high` and an OWASP dependency check to CI (TM-20).

### F9 LOW: CI hardening still open (TM-19 and TM-20)
- `.github/workflows/ci.yml` has no top-level `permissions:`; only the gitleaks job sets one (lines 99-100).
- Actions are pinned by tag, not by commit SHA.
- `contracts.yml:25-33` skips validation if the contract harness is missing instead of failing.
- No dependency scanning runs in CI.

### F10 LOW: the gitleaks allowlist hides more than intended
- `.gitleaks.toml:13-17`: the regex allowlist (`change-me`, `demo-password`, `opaque-demo-token`) applies to the whole repo, so any real secret containing those strings is also hidden.
- **Fix:** limit those regexes to the two fixture paths, matching both path and pattern.
- **Test:** a planted `password = "change-me-<32 hex>"` in a scratch commit fails the scan.

### F11 LOW: parallel requests get past the 10-failures-per-username login limit
- `AuthService.java:85-96` checks the limit, hashes, and only then records the failure. Parallel requests all pass the check before any failure is recorded: up to the 30-request burst per IP, or unlimited through F2.
- **Fix:** reserve the failure slot atomically before hashing, and give it back on success.
- **Test:** 30 concurrent bad logins should run at most 10 hashes.
- Related, INFO: `LoginAttemptLimiter.failures` never removes old keys.

### F12 LOW: a corrected note can still be read back
- `AttemptRepository.java:101-118` never updates `response_body_json`, and `AttemptService.java:219-233` returns it when a submission is retried. So after `PATCH {"notes":""}`, re-sending the original POST with the same key returns 200 with the old notes text.
- Short of deleting the account, there is no way to remove a note.
- The privacy doc (row D07) says notes are kept in revisions, but this replay path is not mentioned.
- **Fix:** keep notes out of the stored replay body and fill in the current notes when replaying, or document the behaviour.
- **Test:** an integration test of exactly this sequence.

### INFO
- **I1 (not verified):** notes containing `\u0000` will most likely cause a 500, because Postgres text cannot store a NUL byte. No test covers it.
- **I2:** the page's security policy (`connect-src 'self'` in `nginx/security-headers.conf:10`) plus the baked-in `VITE_API_BASE_URL=http://127.0.0.1:3000` blocks API calls when the page is opened as `localhost:3000`. The runbook (lines 52-54) says that works; it does not.
- **I3:** a plain `pnpm build` copies `mockServiceWorker.js` into `dist/`. Only the Dockerfile deletes it. The built JS contains no MSW code (checked with grep).
- **I4:** `apps/web/package.json:7-8` starts the Vite dev server with `--host 0.0.0.0`, which exposes it on the local network. That contradicts the 127.0.0.1-only posture of TM-27.

## Verified OK
- **No cross-user access (TM-01, TM-02):** every personal-data SQL statement filters by `user_id` in the query itself, not afterwards.
  - Attempts: `AttemptRepository.java:82,90,106,122`.
  - Decisions: `DecisionQueryRepository.java:60` (decisions); exposures are read only after that ownership check.
  - Preferences: `PreferencesRepository.java:37,52-58`.
  - Consent: `ConsentRepository.java:42,59,89`.
  - Features: `FeatureSnapshotRepository.java:54`, `FeatureRecomputeService.java:36`.
  - Candidates: `CandidateRepository.java:96`.
  - Unknown, malformed, other users' and erased ids all return the same 404 (`DecisionService.java:50-52`, `AttemptService.java:310-319`). PATCH returns 404 before If-Match is checked (tested at `AttemptPatchIT.java:126`).
  - UUIDv7 ids carry 74 random bits from `SecureRandom` (`Uuid7.java`), so guessing them is not practical.
- **Login and sessions (TM-04, TM-21, TM-22 parameters):**
  - Password hashing is argon2id with m=64 MiB, t=3, p=1. Unknown usernames are checked against a dummy hash so they take the same time.
  - Tokens are 32 random bytes; only their SHA-256 is stored. A session needs the token, `revoked_at IS NULL`, an unexpired `expires_at` and an ACTIVE user, all in SQL (`SessionRepository.java:36-39`).
  - Logout revokes the token. Password and username length limits are enforced in `AuthService.java:125-144`.
  - Tests: `AuthSessionIT` (forged, expired, revoked, inactive).
- **Idempotency (TM-06, TM-08):**
  - Keys are scoped to the user, enforced with `ON CONFLICT DO NOTHING` (`AttemptRepository.java:58`). The key format is checked (`AttemptService.java:60`).
  - The request fingerprint is over the canonical (JCS) form of a strictly parsed body; duplicate keys are rejected (`AttemptFields.java:34-51`).
  - Same key with a different body → 409. Tests: `AttemptConcurrencyIT:29` (20 concurrent posts produce 1 row and 1 event; the same key under another user is independent).
- **Notes (TM-10, TM-11):**
  - All SQL is parameterised.
  - The records that carry notes override `toString` so notes never reach a log (`AttemptRow`, `AttemptBody`, `AttemptView`, `AttemptCreate`, `AttemptPatch`).
  - Outbox events are built from an explicit allowlist (`AttemptEvents.java:47-56`) and the event schema rejects extra attributes (`attempts.v1.schema.json:100`).
  - The request body is read raw, so Spring never logs it at DEBUG.
  - Test: `AttemptLogScrubbingIT`.
  - The web app renders notes as plain text (`AttemptRow.tsx:149`); there is no `dangerouslySetInnerHTML` or `innerHTML` in `src`.
- **Logging (TM-16, TM-33):** the access log records method, path template, status, duration and user id only (`RequestLogFilter.java:50-60`). Incoming request ids must match `^[A-Za-z0-9-]{1,64}$`. Unhandled errors return a generic 500. Only the health actuator endpoint is exposed. Docker logs rotate. Tests: `LogScrubbingIT`, `StructuredLoggingIT`.
- **Decision ledger (TM-24, TM-25):**
  - Snapshot digests must be 64 lowercase hex characters before being used in a path, so no path traversal (`SnapshotStore.java:38-42`).
  - Snapshots are written to a temp file and moved atomically; only canonical JSON is accepted.
  - The app role cannot UPDATE or DELETE ledger rows (V6 line 67; tested in `RecommendationLedgerIT:159-172`).
  - Snapshots contain no notes, credentials or raw attempt history (`CandidateSnapshotJson`), and the decision response leaves out the snapshot location and digests.
  - The 64 KiB body limit covers chunked requests too, and `exclude_route_variant_ids` is capped at 100.
- **Compose (TM-27):** all ports bind 127.0.0.1; passwords use `${VAR:?}` with no fallbacks; Postgres roles are separate and not superuser; containers run as non-root with read-only filesystems, dropped capabilities and memory limits.
- **Web image:** nginx hides its version (`server_tokens off`), sends a strict CSP with no inline scripts, `X-Frame-Options: DENY` and `nosniff`. The Docker build context is an allowlist, so `.env`, `data/` and `.git` stay out. The image deletes the MSW worker.
- **Seed script:** the admin password is read from `.env` in a subshell; tokens stay in a mode-700 temp directory; no secret is printed.
- **Licence gate (TM-12):** the importer refuses `synthetic=false` files without an auditable status and an explicit acknowledgement (`CatalogImporter.java:108-122`). The only tracked catalog data is synthetic, plus a Kilter metadata-only manifest.
- **Web session handling (TM-21):** the token lives in sessionStorage, never localStorage. Each request gets a `crypto.randomUUID` request id. Sign out clears the token, cached queries and IndexedDB drafts.
- **Consent (TM-30):** `requireActiveConsent` runs on attempt and recommendation writes.
- **Contract fixes:** C-01, C-02, C-04, C-05 and C-07 are implemented as claimed in `contracts/README.md`. C-03 is a contract-only change and C-06 is still planned (returns 501).

## Contract and doc gaps
- The runbook is wrong about the `localhost:3000` page working (I2) and does not mention the login lockout (F3). The `application.yaml:37-38` comment contradicts the nginx config.
- The privacy doc does not cover notes surviving a correction through the stored replay body (F12).
- The backlog's `dependencyCheckAnalyze` command cannot run because no plugin is configured.
- `planning/04-test-and-evaluation-matrix.md` still has no SEC IDs (GAP-5), so the suggested tests have nowhere to be recorded.

## Summary

| ID | Severity | Status | Area | One line |
|---|---|---|---|---|
| F1 | HIGH | verified (harness) | DoS / TM-22, TM-25 | ~12 concurrent register/login requests run the api out of memory |
| F2 | MEDIUM | verified (replica harness) | Rate limits | `/%761/`, `/v1;x/` paths skip auth and rate-limit filters; public POSTs unlimited |
| F3 | MEDIUM | code-verified | Rate limits | Behind nginx, 10 failed logins lock out all browser users; XFF config inconsistent |
| F4 | MEDIUM | code-verified | Web / TM-32 | Drafts not tied to a user: shown to and auto-submitted under the next account |
| F5 | MEDIUM | code-verified | DoS / TM-25 | One user can fill the global snapshot limit → 503 for everyone |
| F6 | LOW | code-verified | TM-24, TM-27 | api holds the schema-owner password; `attempt_revisions` is writable |
| F7 | LOW | verified (scratch run) | TM-13 | Licensed-data guard misses several file types and SQLite under another name |
| F8 | LOW | verified (`pnpm audit`) | TM-20 | Critical/moderate advisories in dev-only vitest and tinypool; prod clean |
| F9 | LOW | code-verified | TM-19, TM-20 | No workflow-level permissions, tag pins, contracts job skips, no dependency scans |
| F10 | LOW | config-verified | TM-18 | gitleaks allowlist applies repo-wide |
| F11 | LOW | code-verified | TM-22 | Parallel requests exceed the per-username login limit |
| F12 | LOW | code-verified | Privacy | Corrected notes come back on an idempotent retry; no way to delete a note |
| I1–I4 | INFO | I1 not verified | Various | NUL in notes, CSP vs localhost, MSW worker in plain build, dev server on 0.0.0.0 |

The two scratch programs are in `/private/tmp/claude-501/-Users-joshuawu-Beacon-Climb/d626ff7b-83d6-4e55-b9ba-0f7d21a68317/scratchpad/` (`pathprobe/` for F2, `argon/` for F1).
