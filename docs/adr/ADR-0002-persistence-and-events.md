# ADR-0002 — Persistence, events, revisions and idempotency

**Status:** PROPOSED · **Task:** BC-0004 · **Date:** 2026-10-14

Postgres is authoritative. Examples below are normative shapes; UUIDs are illustrative. Error bodies use `{code,message,request_id,field_errors}`. A missing required header is 422 with field error. All timestamps are RFC 3339 strings with an explicit offset; storage uses `timestamptz` and responses normalize to UTC.

## Canonical JSON and identifiers

Use RFC 8785 JSON Canonicalization Scheme (JCS) for request fingerprints and artifact digests. Parse JSON with duplicate object keys rejected, finite IEEE-754 binary64 numbers only, Unicode strings as valid scalar values, and no NaN/Infinity. Serialize the parsed value per RFC 8785 (ECMAScript number formatting, UTF-16 key order, no whitespace), UTF-8 encode, then SHA-256 lowercase hex. Do not hash raw request bytes. Reject numbers outside integer safe range for integer fields; semantic schema validation precedes hashing. `1`, `1.0`, and `1e0` have the same JCS bytes. Cross-language fixture: `{"b":1.0,"a":1}` → `{"a":1,"b":1}`. [BC-0109-JCS]

All internal primary IDs are server-generated UUIDv7. Public route variant ID is `<provider_route_uuid>:<angle_deg>`; split at the final colon and require a canonical integer angle. Request `POST /v1/me/attempts` with `{"route_variant_id":"demo-route-001:35",...}` → 201 with server UUIDv7 `attempt_id`; a client `attempt_id` property → 422 `UNKNOWN_FIELD`. [BC-0109-IDS]

## Timestamp rule

Request `POST /v1/me/attempts` with `occurred_at:"2026-10-14T09:00:00-05:00"` → 201 with `occurred_at:"2026-10-14T14:00:00Z"`, server `received_at`. When `received_at=2026-10-14T14:00:00Z`, `occurred_at:"2026-10-14T14:05:01Z"` → 422 `OCCURRED_AT_IN_FUTURE`. Exactly five minutes is accepted. Past dates are allowed; the stream reconciliation rule handles late events. [BC-0109-TIME]

## Idempotent create

`Idempotency-Key` is required, 1–128 printable ASCII characters (U+0021–U+007E), scoped to `(authenticated user_id, key)` forever until erasure. Fingerprint is SHA-256 of JCS request body. Persist the logical response body in the same transaction as the attempt and outbox so a replay returns that body with HTTP 200; `X-Request-Id` reflects the current HTTP request. `INSERT ... ON CONFLICT (user_id,submission_idempotency_key) DO NOTHING RETURNING *`; if no row, read the committed row and compare fingerprints. Concurrent requests wait on the unique index; no second event is written. Same key under a different user is independent.

Example: `POST /v1/me/attempts`, `Idempotency-Key: k1`, body `{"route_variant_id":"demo-route-001:35","occurred_at":"2026-10-14T14:00:00Z","attempts_count":2,"sent":true}` → 201 `{"attempt_id":"018f0000-0000-7000-8000-000000000001","revision":1,"status":"ACTIVE",...}`. Repeat with semantically equal JCS body and key → 200 same body. Change `sent` to false with `k1` → 409 `{"code":"IDEMPOTENCY_CONFLICT","message":"Key was used with a different request","request_id":"r2","field_errors":[]}`. [BC-0109-IDEMPOTENCY, BC-0109-CONCURRENT-20]

## Revisions and audit

`PATCH /v1/me/attempts/{attempt_id}` requires `If-Match: "<revision>"` (quoted decimal). It is not independently idempotent; a retry with the old revision returns 409 and cannot apply twice. Each accepted change atomically increments revision and writes an immutable `attempt_revisions` row plus outbox event. Retraction sets `status=RETRACTED`, keeps audit history, and contributes zero to features. No PATCH may restore a retracted attempt in v1.

Example: `PATCH .../018f...001`, `If-Match: "1"`, body `{"sent":false}` → 200 `{"attempt_id":"018f...001","revision":2,"status":"ACTIVE","sent":false,...}`. Repeat → 409 `{"code":"REVISION_CONFLICT","message":"Attempt revision changed","request_id":"r3","current_revision":2,"field_errors":[]}`. `If-Match: "2"`, `{"retract":true}` → 200 `{"revision":3,"status":"RETRACTED",...}`. Cross-user ID → 404. [BC-0109-REVISION, BC-0109-RETRACT, BC-0109-OWNERSHIP]

## Transactional outbox and events

The same transaction writes `attempt_event_outbox(event_id UUIDv7 PK,aggregate_id,aggregate_revision,event_type,schema_version,payload_json,created_at,published_at NULL)`. Publisher polls `FOR UPDATE SKIP LOCKED`, emits to `attempts.v1` keyed by `user_id`, then marks `published_at`; a crash can publish twice. Consumers select only the highest revision per `attempt_id` and dedupe `(attempt_id,attempt_revision)`. Example accepted POST above → one `ATTEMPT_RECORDED` outbox row revision 1; retry → zero new rows. Publish/crash/retry → two Kafka deliveries, one canonical contribution. [BC-0109-OUTBOX, BC-0302-DEDUP, BC-0307-PUBLISHER-CRASH]

Events are JSON Schema 2020-12, major version in topic suffix. Within a major, producers may add optional fields only after all consumers tolerate them; required fields and meanings remain stable. `attempts.v1`: six partitions, seven-day retention. `climber-features.v1`: key `user_id`, compacted; `stream-dlq.v1`: 30-day retention. These are planned defaults, not deployed configuration. Example `ATTEMPT_CORRECTED` v1 envelope validates; adding a required top-level field or changing `sent` from boolean to string fails compatibility. [BC-0006-SCHEMA, BC-0302-COMPAT]

## Erasure

An erasure request marks `users.deleted_at`; after 30-day grace a job deletes attempts, audit, outbox and snapshots. Ledger row retains only `decision_id` and `erased=true`; replay returns `REPLAY_UNAVAILABLE_ERASED`. Kafka retention and local snapshot deletion are part of the hard-delete check. Example `DELETE /v1/me` (planned) → 202 erasure pending; replay after hard delete → 410 `REPLAY_UNAVAILABLE_ERASED`. `docs/privacy-and-retention.md` is owned by BC-0007 and must resolve the operational schedule. [BC-0412-ERASURE]

## PRD cross-check

PRD §6.3 duplicate/reorder invariant is enforced by BC-0302 and parity tests; §8.3 Kafka outage preserves the Postgres transaction, DB outage returns 503, and model timeout behavior belongs to ADR-0006. Redis behavior is superseded by ADR-0001's Postgres read model.

## Enforcing test matrix

| Rule | Example input / request | Expected output / response | Test ID |
|---|---|---|---|
| Server IDs and public variant ID | POST attempt with `demo-route-001:35`; then POST with `attempt_id` | 201 server UUIDv7; then 422 `UNKNOWN_FIELD` | BC-0109-IDS |
| RFC 8785 fingerprint | Bodies `{"b":1.0,"a":1}` and `{"a":1,"b":1}` | Same SHA-256 fingerprint | BC-0109-JCS |
| Offset and future skew | `2026-10-14T09:00:00-05:00`; then server time + 5m01s | 201 UTC `14:00:00Z`; then 422 `OCCURRED_AT_IN_FUTURE` | BC-0109-TIME |
| Key scope and concurrency | 20 simultaneous POSTs, same user/key/body; another user/key | One 201, nineteen 200, one row/event; other user gets independent 201 | BC-0109-CONCURRENT-20 |
| Conflicting key | Same user/key, changed `sent` | 409 `IDEMPOTENCY_CONFLICT` | BC-0109-IDEMPOTENCY |
| Revision and retraction | PATCH `If-Match: "1"` then repeat; PATCH `retract:true` with current revision | 200 revision 2; 409 `REVISION_CONFLICT`; 200 `RETRACTED` revision 3 | BC-0109-REVISION, BC-0109-RETRACT |
| Audit and ownership | Successful PATCH; GET attempt under a different user | Immutable audit row and 404 | BC-0109-REVISION, BC-0109-OWNERSHIP |
| Outbox atomicity and duplicate delivery | POST followed by publisher crash/retry | 201 plus one outbox row; two deliveries converge to one contribution | BC-0109-OUTBOX, BC-0307-PUBLISHER-CRASH |
| Event contract and evolution | Valid `ATTEMPT_CORRECTED`; then change `sent` to string | Schema accepts first, rejects second | BC-0006-SCHEMA, BC-0302-COMPAT |
| Erasure | DELETE `/v1/me`; replay after hard-delete job | 202 pending; then 410 `REPLAY_UNAVAILABLE_ERASED` | BC-0412-ERASURE |
