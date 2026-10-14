# ADR-0005 — Authentication and session model

**Status:** PROPOSED · **Task:** BC-0013 · **Date:** 2026-10-14

## Decision
- Local pseudonymous accounts: `username` (no email required), password hashed with argon2id (Spring Security `Argon2PasswordEncoder`, Argon2id parameters: 16-byte salt, 32-byte hash, 64 MiB memory, 3 iterations, parallelism 1; tune only with a recorded local benchmark. Passwords are never logged).
- `POST /v1/auth/session` returns an opaque 256-bit random bearer token; only its sha256 is stored in `sessions(token_hash, user_id, expires_at, revoked_at)`. TTL 24 h; revocation on logout, password change and erasure.
- Every `/v1/me/**` handler resolves the user from the token; cross-user access returns `404` (not `403`) per threat model to avoid id enumeration.
- Admin endpoints require role `ADMIN` assigned by seed script only; `403` otherwise.
- Rate limits: 10 login attempts / 15 min per username and per IP.
- OIDC seam: `IdentityProvider` interface with the local implementation; adding an external IdP is a new ADR.

## Rejected
Hosted IdP in MVP (adds secrets, network dependency, cost); JWTs (revocation complexity for no benefit at this scale).
