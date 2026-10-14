# Wireframe: Engineering Observatory (stub)

Contract basis: `GET /health/live`, `GET /health/ready` (live, public, `Health` schema), `GET /v1/admin/experiments` and `POST /v1/admin/experiments/{id}/rollback` (both `x-planned: true`, 501 in v1) in `contracts/openapi.yaml` 1.0.0; PRD 3.1 Engineering Observatory, 9.2, 9.3, 11; US-09. Journey: `../journeys.md` J7.

## 1. Purpose

An admin/demo-only status page. In v1 it can show only what the contract provides: whether the API is alive and ready. Everything else the PRD lists (stream lag, watermark and checkpoint health, feature freshness, inference latency, model version, rollback drill) has no endpoint, so those rows are shown as "NOT CONNECTED" with an explanation and **no numbers, charts or sample values**. The rollback drill control is absent apart from a text line.

It is a plain status list, not a chart dashboard: each signal has a name, a status stamp (text plus glyph), a one-line meaning, and the time it was checked.

## 2. Wireframe, mobile (320-480px)

Route `/observatory`. Reachable from "More > Engineering" in demo builds only (see needs contract change on roles). The page makes only the two public health calls, on load and on "Check again" (no polling in v1).

```
+------------------------------------------+
| BEACON CLIMB   [Synthetic demo]          |
+------------------------------------------+
| Engineering Observatory             (h1) |
| [DEMO / ADMIN PREVIEW]                   |
| Only API health is connected in this     |
| version. Other signals are listed so     |
| you can see what's coming.               |
| [ Check again ]   Checked 14:05:12       |
+------------------------------------------+
| Connected signals                   (h2) |
| (*) API alive          [UP]              |
|     GET /health/live                     |
| (*) API ready          [UP]              |
|     GET /health/ready                    |
|     Request id fixture-request-1         |
+------------------------------------------+
| Not connected yet                   (h2) |
| (:) Stream lag             [NOT CONNECTED]|
| (:) Watermark / checkpoint [NOT CONNECTED]|
| (:) Feature freshness      [NOT CONNECTED]|
| (:) Inference latency      [NOT CONNECTED]|
| (:) Model version          [NOT CONNECTED]|
| (:) Rollback drill         [NOT CONNECTED]|
|   Each shows: "No data source is         |
|   connected for this in this version."   |
+------------------------------------------+
```

Glyphs: `(*)` filled circle = UP, `(x)` cross-in-square = DOWN, `(:)` dotted circle = NOT CONNECTED; the status word is always present.

### >= 1024px

Single main column (status list, `--size-reading-max` wider) with an `aside` "What each signal will mean" listing one sentence per not-connected row, taken from PRD 3.1/9.2 (descriptions only). Nav rail on the left.

## 3. Fields and contract sources

| UI element | Contract source | Direction | Notes |
|---|---|---|---|
| API alive | `GET /health/live` 200 `status: "UP"` | read | any other result -> DOWN |
| API ready | `GET /health/ready` 200 `status: "UP"` or 503 `ErrorEnvelope` | read | 503 -> "NOT READY" with `code`, `message` |
| Request id | `X-Request-Id` response header, `ErrorEnvelope.request_id` | read | shown in a details line for diagnosis |
| Checked time | client clock when the response was received | local | label "Checked <time>" |
| Not-connected rows | static text (PRD 3.1, 9.2) | none | no request, no values |
| Experiments, rollback | `GET /v1/admin/experiments`, `POST .../rollback` | none | **never called in v1** (planned, excluded from mocks) |

## 4. Board and hold states

Not applicable: no board SVG on this screen.

## 5. State matrix

| State | What is shown | What is actionable | Copy |
|---|---|---|---|
| Loading | Both connected rows show glyph (:) with "CHECKING"; button inert with `aria-busy`; not-connected list renders immediately (static) | none while checking | Live: "Checking API health" |
| Empty | Not applicable in the normal sense: the Not-connected list is the empty state for those signals | n/a | Each row: "No data source is connected for this in this version." |
| Error 4xx | `/health/*` are public; a 400 renders as DOWN with the envelope message. No 401/403/404 are declared | "Check again" | "The API answered with an error: <message>. Request id <id>." |
| Error 5xx / network | `/health/ready` 503: "API ready" row = NOT READY with `code`/`message`. Network failure or timeout: both rows DOWN with "No response" | "Check again" | 503: "The API is up but not ready to serve requests." Network: "No response from the API." |
| Validation 422 | Not applicable | n/a | n/a |
| Conflict 409 | Not applicable | n/a | n/a |
| Insufficient data | Every not-connected signal. Never show zero, a flat line, or a sample | none | "No data source is connected for this in this version." Rollback row: "The rollback drill isn't available in this version." |
| Offline | Browser offline: rows keep the last result with its time and a "SAVED COPY" stamp; "Check again" disabled with reason | none | "You're offline. These results are from 14:05 and can't be refreshed." |

## 6. Accessibility

### Landmarks and headings

| Landmark | Content |
|---|---|
| `header` | brand, synthetic stamp |
| `nav aria-label="Primary"` | links; "More" lists Engineering in demo builds |
| `main` | status lists |
| `section aria-labelledby` x2 | Connected signals, Not connected yet |
| `aside aria-label="What each signal will mean"` (>= 1024) | descriptions |
| `footer aria-label="Data and privacy"` | data note |

Heading outline: `h1` Engineering Observatory; `h2` Connected signals; `h2` Not connected yet. Each signal is a `li` in a `ul` (or `dl` pairs name/status) with the name as text, not a heading.

### Keyboard path

Tab order: skip to main -> nav -> Check again -> (status rows are static text, no stops) -> the "Request id" details disclosures if present -> end. No roving widgets; no board.

- After Check again finishes, a polite live region announces "API alive: UP. API ready: UP" (or the failing status); focus stays on the button.
- Status is never carried by color: each row has glyph shape, status word and (for failures) explanatory text.

### Focus and motion

- Two-tone `:focus-visible` ring.
- No animation beyond a 150 ms opacity change of a status stamp; none under `prefers-reduced-motion: reduce`. No spinner loop longer than the request itself; checking state is text.

## 7. Needs contract change (this screen)

1. `GET /v1/me` has no `role`/`is_admin`; nothing lets the client hide Observatory from non-admins (ADR-0005: admin role assigned by seed script; `403` otherwise). Interim: demo-build flag only; the page exposes only public health, no private data.
2. Every other signal needs an endpoint with a defined shape: stream lag, watermark/checkpoint, feature freshness, inference latency, serving model version, experiment state and rollback status. `GET /v1/admin/experiments` and `POST .../rollback` exist only as 501 placeholders (response shapes undefined, including the state names `CANARY`, `ROLLBACK_PENDING`, `ROLLED_BACK` from PRD 9.3).
3. `Health` has only `status: "UP"`; no component breakdown (database, Kafka, model) and no version or build id.
4. Polling interval and freshness thresholds for any future live signals are undefined; none are assumed here.
