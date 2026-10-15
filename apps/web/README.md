# Beacon Climb web app (`@beacon/web`)

Vite 6, React 19, TypeScript (strict), React Router 7, TanStack Query 5, a typed client
generated from `contracts/openapi.yaml` (1.1.0) and MSW mocks built from
`contracts/examples`. Scaffolded in BC-0114; onboarding (BC-0115), explore (BC-0116), the
session builder and decision detail (BC-0117) and the attempt log (BC-0118) are built
against the mocks; BC-0121 proves them against the real API.

## Run

```sh
pnpm install                       # repo root
pnpm --filter @beacon/web dev      # http://localhost:5173, against mocks (VITE_USE_MOCKS=true)
pnpm --filter @beacon/web dev:api  # same, against the real API (no mocks)
```

| Variable            | Default                 | Meaning                                                      |
| ------------------- | ----------------------- | ------------------------------------------------------------ |
| `VITE_API_BASE_URL` | `http://127.0.0.1:8080` | Base URL for every request made by `src/api/client.ts`.      |
| `VITE_USE_MOCKS`    | unset (`false`)         | `true` starts the MSW worker in `main.tsx` before rendering. |

Copy `.env.example` to `.env.local` to override (gitignored). `dev` sets `VITE_USE_MOCKS=true`
inline so the mocks are on by default during UI work; `dev:api` leaves it unset and talks to
`services/api` on 8080. The API must allow `Authorization`, `X-Request-Id`, `Idempotency-Key`
and `If-Match` request headers in CORS, and expose `X-Request-Id` and `Retry-After`.

Both dev scripts bind `127.0.0.1` only (BC-0123 I4, matching the compose stack's loopback-only
posture). To test from a phone on the LAN, opt in explicitly; the last `--host` wins:

```sh
pnpm --filter @beacon/web dev --host 0.0.0.0
```

## Mocks

- `src/mocks/handlers.ts`: one handler per implemented operation (19; `x-planned` operations
  have none). Bodies, statuses and `X-Request-Id` headers are imported straight from
  `contracts/examples/<operationId>/response*.json`; nothing is copied.
- Stateful behaviour: `registerUser` returns the token fixture, then `409 USERNAME_TAKEN` for a
  repeated username; `createSession` returns the 422 fixture for a short password.
  Bearer-protected operations answer `401 UNAUTHENTICATED` without an `Authorization` header,
  unknown ids are `404 NOT_FOUND`, and `createAttempt` / `createRecommendations` answer
  `403 CONSENT_REQUIRED` while the per-process profile (after `registerUser`) has no consent.
  `resetMockState()` resets it all (done after every test).
- Offline: when the page reports `navigator.onLine === false` (Playwright's
  `context.setOffline(true)`) every handler answers with a network error
  (`HttpResponse.error()`), because the service worker would otherwise keep answering while
  the browser is offline.
- Attempts (BC-0118, `src/mocks/state.ts` + `src/mocks/recommend.ts`): an in-memory attempt
  store seeded with the `listAttempts` fixture rows. `createAttempt` validates the body
  (`attempts_count >= 1`, `occurred_at` at most five minutes ahead -> the committed 422
  fixtures; optional-field ranges -> a derived `VALIDATION_FAILED`) and is stateful per
  `Idempotency-Key`: first call 201 with a generated attempt (ids
  `018f0000-0000-7000-8000-a<counter>`; the committed request body maps onto the committed
  attempt), same key + same canonical body -> 200 with the stored attempt, same key +
  different body -> the 409 fixture. `getAttempt` and `listAttempts` serve the store;
  listing is newest first by `occurred_at` then `attempt_id` (A11), `limit` 1..100,
  `status` filter, cursor = base64url of `"<occurred_at>|<attempt_id>"` (`INVALID_CURSOR`
  otherwise). `patchAttempt` requires `If-Match` to equal the stored revision (quoted),
  answers the 409 fixture with the real `current_revision` otherwise, applies the patch,
  bumps the revision and sets `RETRACTED` for `retract: true`.
- Recommendations and decisions (BC-0117): `createRecommendations` serves the committed
  response for the committed request body; any other request gets a deterministic lineup
  from the synthetic catalog (routes at the requested angle, rotated by a hash of
  `session_id`, `exclude_route_variant_ids` skipped, `position` 1..n, `rank_score`
  descending, reason codes from the catalog facts, `cold_start` while fewer than five ACTIVE
  attempts exist, `fallback_used: false`, `predicted_send_probability: null`). It validates
  `max_routes` (422 fixture for < 1, derived `VALIDATION_FAILED` for > 20), `board_id`
  (`UNKNOWN_BOARD`) and `angle_deg` (`UNSUPPORTED_ANGLE`). Every call records a `Decision`
  (`created_at`, the request) that `getDecision` serves; the fixture decision id still
  serves the fixture. Nothing in the lineup is a ranking: the real policy lives in
  `services/api`.
- Profile (BC-0115): `GET /v1/me` serves the fixture (a returning user with consent and
  preferences) until `registerUser` runs in the same page/process; a registered user starts
  with its own `user_id` (`mockUserId(username)`, a deterministic hash of the name) and
  `username`, `consent: null, preferences: null`, and `createConsent` / `putPreferences` fill
  them in, so the onboarding flow can be walked end to end and two registrations are two
  distinguishable users (BC-0123 F4). `createSession` switches back to the fixture profile. `putPreferences` echoes the validated body (equal to the fixture for the
  fixture request) and answers a derived `422 UNSUPPORTED_ANGLE` with a `field_errors` entry
  for `angle_deg` when the angle is not in the fixture board's `available_angles`; the
  committed `response-422.json` (goals type error) is still served when `goals` is not an
  array.
- Catalog (BC-0116): `listRoutes` and `getRoute` serve `src/mocks/catalog.ts`, a deterministic
  synthetic catalog derived from the fixtures: the committed route (`synthetic-route-001:35`)
  verbatim plus 59 generated variants, 20 per available angle (ids `synthetic-route-NNN:<angle>`,
  placements on the fixture board's holds, grades 10..30 with some ungraded, observation counts
  0..36, a few with tags). Filters (`board_id`, `angle_deg`, `grade_min`, `grade_max`) and
  `limit` apply; the cursor is base64url of `"<grade_numeric|null>|<route_variant_id>"` of the
  last row. Derived 422 envelopes: `UNKNOWN_BOARD`, `UNSUPPORTED_ANGLE`, `INVALID_CURSOR`; the
  `limit > 100` fixture is unchanged. Generated routes have `grade_label: null` because the
  grade table is still blocked; nothing in the catalog is a measurement.
- `src/mocks/browser.ts` (service worker, `public/mockServiceWorker.js` generated by
  `msw init`) and `src/mocks/server.ts` (Node, wired in `src/test/setup.ts`). Vite copies
  `public/` into `dist/`, so the `postbuild` script deletes `dist/mockServiceWorker.js`
  after every `pnpm build` (BC-0123 I3); the worker is only ever served by the dev server
  with `VITE_USE_MOCKS=true`. The Dockerfile deletes it again, belt and braces.

## Generated client

```sh
pnpm --filter @beacon/web generate:api   # contracts/openapi.yaml -> src/api/generated/schema.d.ts
```

The generated file is committed; `src/api/generated/schema-drift.test.ts` regenerates it to a
temp directory and fails if it differs, so a contract change without `generate:api` fails CI.
`src/api/client.ts` wraps `openapi-fetch` with a middleware that adds `Authorization: Bearer`
from the token store and a fresh `X-Request-Id` (UUID) per request. `call()` / `unwrap()` turn
any failure into `ApiError` (`status`, `code`, `message`, `requestId`, `fieldErrors`,
`currentRevision`, `retryAfterSeconds`) from `src/api/errors.ts`.

Token store (`src/features/auth/token-store.ts`): memory first, mirrored to `sessionStorage`
so a reload in the same tab keeps the session. Documented trade-off (BC-0115): readable by any
script on the origin, so the app depends on a strict CSP; `localStorage` is deliberately not
used. The session also carries `userId` (`Me.user_id`) once `GET /v1/me` has answered for the
token: `useMe`'s query function calls `recordSignedInUser` (`signed-in-user.ts`), which stores
the id for the tab and, the first time it is known in the tab or whenever it changes, purges
every attempt draft owned by another account (see "Attempt log"). `useCurrentUserId` exposes
it to components. The client resolves `globalThis.fetch` per request (not at creation) so
MSW's Node interceptor, installed after module import, is always used in tests.

Sign out (`src/features/auth/sign-out.ts`, header button in the shell while a token exists):
`DELETE /v1/auth/session`, then the token, the query cache and the attempt drafts are cleared
locally even if that request failed, and the app returns to `/onboarding`. A 401 elsewhere
only clears the token (and with it the tab's user id): drafts stay on the device but are
invisible until their owner signs in again (journeys.md, "Your drafts are still on this
device"); a different account signing in deletes them.

## Canonical JSON

`src/lib/jcs.ts` is the TypeScript RFC 8785 canonicaliser (`canonicalize`, `serialize`,
`sha256Hex`, `fingerprint`). `src/lib/jcs.test.ts` runs every case in
`contracts/jcs/fixtures.v1.json`, the same file the Node reference
(`test/contracts/jcs.test.cjs`) and the Java implementation (BC-0109) use.

## Onboarding (BC-0115)

`/onboarding/:step` with steps `account`, `consent`, `board`, `goals` (`src/features/onboarding`).
One `h1` ("Set up Beacon Climb"), the step title is an `h2` that takes focus on every step
change, and a visible progress list marks the current step. Resume logic: with a stored token
the page fetches `GET /v1/me` and skips what is done (consent present -> board step,
preferences present -> `/explore`); a 401 clears the token and shows the account step.

- Account: `POST /v1/auth/register`; `409 USERNAME_TAKEN` shows an inline "Sign in instead"
  that turns the same form into `POST /v1/auth/session`; 422 `field_errors` map onto the
  fields; 429 shows `Retry-After`; every other failure is a form-level banner with the
  request id. The token goes to the token store (memory + sessionStorage, see above).
- Consent: the document from `GET /v1/consent/current` rendered as text nodes (headings,
  paragraphs, lists; no HTML injection), version stamp, unchecked required `service` box
  that gates the button, optional `research` box off by default; sends
  `{consent_version, scopes, text_sha256}` from the displayed document.
- Board and angle: boards from `GET /v1/boards`; the angle is a radio group over the chosen
  board's `available_angles`, so an unsupported angle cannot be picked; a server
  `UNSUPPORTED_ANGLE` / `UNKNOWN_BOARD` field error from the final save brings the person back
  to this step with the message attached (`aria-describedby`, `role="alert"`).
- Level and goals: `GradeBand` radios with plain-language "where the first picks start" text
  (never a claim about the person), up to four `Goal` checkboxes, `PUT /v1/me/preferences`,
  then `/explore`.

## Explore (BC-0116)

`/explore` (`src/features/explore`) needs a session and preferences, otherwise it redirects
to `/onboarding`. Filters live in the URL (`angle_deg`, `grade_min`, `grade_max`) and mirror
the `listRoutes` query; `board_id` always comes from preferences and the angle from the
board's `available_angles`, so an incompatible request is never built. The list is a
TanStack `useInfiniteQuery` with a "Load more routes" button driven by `next_cursor`, ten rows
per page, and a `role="status"` line "n routes loaded". Rows show grade (`grade_numeric`,
label when present, "ungraded" otherwise), observation count and a "Low evidence" stamp under
10 observations. `/explore/:routeVariantId` shows the route on `BoardSvg`, grade, evidence,
tags or the "Style tags: not available for this route." notice, and a back link.

The route detail also links to `/attempts/new?route_variant_id=…` ("Log an attempt on this
route").

`src/components/board-svg/BoardSvg.tsx` is pure: `viewBox` from `Board.geometry` (y flipped
once when `y_axis` is `UP`), every hold as a muted dot, placements as role shapes (START
double ring, HAND filled circle, FOOT triangle, FINISH ring with a bar) each with a
`<title>`, `role="img"` with a count summary, and a visually hidden ordered list of
placements ("Start hold h1 at 0.25, 0.50 (middle left)") referenced by `aria-describedby`.
`BoardLegend` prints the shape key in words. Nothing animates.

## Session builder and decisions (BC-0117)

`/session` (`src/features/session`) needs a session and preferences (otherwise a redirect to
onboarding, or a "finish setup" notice when preferences are missing). Goal (`Goal` enum with
the plain-language copy from `src/features/onboarding/goals.ts`), route count (UI 3..6, the
contract allows 1..20) and the client `session_id` live in the URL (`?session_id=&goal=&routes=`);
the id is generated once per visit with `crypto.randomUUID()` so a reload reuses it.
"Build my session" sends `POST /v1/me/sessions/recommendations` with `board_id` and
`angle_deg` from `GET /v1/me` preferences. The lineup is an `ol` in `position` order: route
name (link to explore), grade or "ungraded", observation count, up to three reason chips
(`src/features/decision/reason-codes.ts`, every `ReasonCode` -> label + one-sentence
explanation; unknown codes print as "Other reason: CODE"), "+N more" to the decision,
`predicted_send_probability: null` -> "No personalised estimate yet" with the uncertainty
label, `cold_start` -> "Recommendations are heuristic until you log a few attempts",
`fallback_used` -> warning banner and a "Simplified recommendation" stamp on every card.
"Swap" re-requests with the route added to `exclude_route_variant_ids` (same count, same
`session_id`); "Dismiss" does the same with one route fewer; the dismissed list is shown with
a Reset. Each card links "Log attempt" (`/attempts/new?route_variant_id=…`) and "Why this
route" (`/decisions/:id?position=n`). Errors: `403 CONSENT_REQUIRED` links to the consent
step, 422 maps `field_errors` onto goal / route count or links to setup, 503 and network
failures show the request id with a manual "Try again" while the previous lineup stays
visible, dimmed, stamped "Not refreshed". The lineup heading takes focus after every build and
a polite live region announces the result. The successful response is written into the
decision query cache so the detail page opens without a second request.

`/decisions/:decisionId` (`src/features/decision`, `GET /v1/me/decisions/{decision_id}`):
request context (goal, board, angle, max_routes, excluded ids, session id, `created_at`),
versions (`policy_version`, `model_version`, with `none` printed as "no learned model in
use", `feature_as_of`), the cold-start / fallback flags as banners and text, and the ranked
list with every reason explained, `rank_score` with three decimals labelled "relative order
only", the estimate line and a "Log an attempt" link per route. Malformed ids and 404 share the
"not found or not yours" state (ADR-0005).

## Attempt log (BC-0118)

`/attempts/new?route_variant_id=…` (`src/features/attempts/AttemptFormPage.tsx`, reachable
from session cards, the decision page and the explore detail): `occurred_at`
(`datetime-local`, default now; the client rejects more than five minutes ahead, mirroring
`OCCURRED_AT_IN_FUTURE`), `attempts_count` stepper (>= 1), `sent` switch, progress slider in
percent (sent as `completion_fraction`, only when not sent and only when touched),
perceived effort 1..10 (optional), notes (<= 500 with a counter; rendered as text only). A
consent gate (`GET /v1/me`) disables Save and links to the consent step.

Draft model (`src/features/attempts/draft-store.ts`): `{draft_id, idempotency_key,
owner_user_id, body, state: draft | sending | synced | failed, attempt, error, created_at,
route_name}`, persisted in IndexedDB (`beacon.drafts.v1`, store `drafts`) through a small
promise wrapper with an in-memory fallback when IndexedDB is missing or fails to open (jsdom,
private mode). `route_name` is display-only. The draft id and its Idempotency-Key are generated
once when the form opens; the record is written on the first Save and every retry reuses it,
so a double click (synchronous guard + per-draft in-flight promise in `attempt-sync.ts`), a
lost reply or a reconnect can never create two attempts; a 200 replay counts as saved. A draft
found in `sending` after a reload becomes `failed` with an unknown-outcome message. Failures:
network / 5xx / 429 keep the draft with "Retry" (same key) and "Keep as draft"; `409
IDEMPOTENCY_CONFLICT` explains and offers "Create new draft" (new key); 422 maps
`field_errors`; `403 CONSENT_REQUIRED` links to the consent step. `useReconnectSync` retries
failed-but-retryable drafts once on the `online` event, and once when the browser is online
and the user is known (on mount, or as soon as `GET /v1/me` answers); a synced draft navigates
to the log and is removed from the device once the server list shows it.

Draft ownership (BC-0123 F4): IndexedDB is per origin, not per account, so on a shared browser
a draft (with its notes) left by one person would otherwise be shown to, and auto-sent under
the token of, the next. Every draft therefore records `owner_user_id`, the `Me.user_id` the
tab learned from `GET /v1/me`; the form keeps Save disabled (with a retry banner if the
profile request failed) until that id is known. `useDrafts`, `syncFailedDrafts` and the
"Retry" path only consider drafts whose owner equals the tab's current user id
(`draftStore.listFor`), and `submitDraft` refuses any other draft without a request. Drafts of
other owners are deleted (`draftStore.purgeForeign`) when `GET /v1/me` resolves for a user the
tab did not know yet or a different one, i.e. right after sign-in or registration; drafts
written before this field existed have no owner and count as foreign. A 401 clears the token
and the tab's user id, so drafts become invisible until their owner signs in again; "Sign out"
still deletes every draft on the device.

`/attempts` (`AttemptsPage.tsx`): drafts not yet synced on top ("Waiting to save") with their
state stamp, Retry / Discard / "Log a new attempt"; then `GET /v1/me/attempts` newest first,
20 per page, "Load more" on `next_cursor`. Rows show route and grade, local time, attempts,
Sent / Not sent, progress, effort, notes, "Revision N", "Corrected" (revision > 1) and
"Retracted". "Correct" opens an inline form (count, sent, progress, effort, notes; route and
time are not patchable) and sends only the changed fields with `If-Match: "<revision>"`;
"Undo (retract)" confirms inline, then `PATCH {retract: true}`. A `409 REVISION_CONFLICT`
shows "This attempt changed elsewhere (revision N). Refresh" from `current_revision`; Refresh
re-reads with `getAttempt`, updates the row and the open form, and the next Save uses the new
revision.

## Checks and budget

```sh
pnpm --filter @beacon/web lint      # eslint + prettier --check
pnpm --filter @beacon/web test      # vitest (jsdom + MSW + Testing Library + axe-core)
pnpm --filter @beacon/web build     # tsc -b && vite build
pnpm --filter @beacon/web size      # size-limit: all dist/assets/*.js, gzip, limit 150 kB
pnpm --filter @beacon/web test:e2e  # playwright (chromium, dev server + mocks on :4173)
```

### Playwright suite

Playwright (`playwright.config.ts`, specs in `e2e/`) starts `pnpm dev` with mocks on port 4173
(`reuseExistingServer: false`), Chromium only, `timezoneId: UTC` so fixture timestamps render
the same on every machine. Reporters: `list` plus `html` written to `playwright-report/`
(gitignored; open it with `pnpm --filter @beacon/web exec playwright show-report`). Projects:

- Flow specs: `onboarding-375/1280` run the whole first-run flow; `session-375/1280` resume a
  seeded user, build a session, dismiss a route and open the decision; `attempts-375/1280` log
  with a double click, correct, hit a revision conflict made "elsewhere" (a direct `fetch` from
  the page against the in-page mocks), save while offline (`context.setOffline`), then sync,
  and reproduce the BC-0123 F4 shared-browser case (A drafts offline and closes the page, B
  registers in a new page of the same context: A's draft is not listed, is purged from
  IndexedDB and no `POST /v1/me/attempts` carrying it is seen by `page.on('request')`).
- Quality bar (BC-0119): `bp-320`, `bp-375`, `bp-768`, `bp-1024`, `bp-1440` and `bp-1920` run
  `a11y.spec.ts` and `responsive.spec.ts` over the twelve page states in `e2e/states.ts`
  (onboarding account / consent / board / goals, explore list, route detail, session builder
  before and after a lineup, decision detail, attempt log with server rows and a draft that
  needs attention, attempt form with a validation error, 404). `keyboard` (1024) runs
  `keyboard.spec.ts`; `reduced-motion` (1024 with `prefers-reduced-motion: reduce`) runs
  `reduced-motion.spec.ts`. States are deterministic: a fixed `session_id` (the mock lineup is
  rotated by its hash), the fixture decision id, `page.clock.setFixedTime` on the attempt form
  and a draft seeded straight into IndexedDB.

What each gate asserts:

- `a11y.spec.ts`: `@axe-core/playwright` on every state at every breakpoint; serious or
  critical violations fail the test. Moderate and minor ones, and a per-page rule summary,
  are test annotations (`axe-moderate`, `axe-minor`, `axe-summary`): in the HTML report open
  the test and read its "Annotations" block (the list reporter does not print them).
- `responsive.spec.ts`: `document.documentElement.scrollWidth <= window.innerWidth` (no
  sideways scroll; `body { overflow-x: clip }` does not hide overflow from this check) and a
  visible main landmark at all six widths; at 320 / 768 / 1024 / 1440 a viewport-only
  `toHaveScreenshot` per state, baselines in `e2e/__screenshots__/<project>/<state>.png`
  (48 PNGs; the BC-0116 `explore` and `route-detail` shots now live here).
- `keyboard.spec.ts`: Tab reaches the skip link first and Enter lands focus on `main`; the
  account step, the explore rows, the lineup's Swap / Dismiss and the attempt form work with
  Tab, Enter and Space alone; the first 15 Tab stops on eight pages each show a computed focus
  ring (outline or box-shadow; for an invisible native input the parent that carries
  `:has(input:focus-visible)` is inspected).
- `reduced-motion.spec.ts`: the media query matches and no element on explore, the lineup or
  the attempt form keeps an animation or transition longer than 0.01 ms, the value every
  duration token collapses to under reduced motion (not 0, so `transitionend` still fires).

Baselines were generated on macOS with headless Chromium. The screenshot comparison runs only
when `process.platform === 'darwin'` (annotation `screenshot-skipped` otherwise), so the Linux
`web-e2e` job in `.github/workflows/ci.yml` passes the overflow and landmark checks without
pixel baselines and uploads `playwright-report/` and `test-results/` (traces on failure) as
artifacts. To compare pixels in CI as well, run
`pnpm --filter @beacon/web exec playwright test --update-snapshots` once on Linux into a
platform-specific directory (add a `{platform}` segment to `snapshotPathTemplate`) and drop
the darwin guard. After an intended visual change, regenerate with the same command and review
the PNG diffs before committing. Browsers: `pnpm --filter @beacon/web exec playwright install
chromium`. Lighthouse is not installed in this repo, so no performance budget is recorded by
this suite yet (the ticket plans it as recorded, not asserted).

The budget is the planning/06 §11 figure (< 150 kB gzipped app JS). It is measured on the
built files with `@size-limit/file` (no re-bundling) and sums every JS chunk, including the
lazy route chunks, so it is conservative. `vite.config.ts` keeps it under budget with
`build.target: 'es2022'` (no down-levelling of class fields and friends; Safari 15.4+),
`modulePreload.polyfill: false` (browsers without `modulepreload` simply do not preload) and
Rollup's `experimentalMinChunkSize` (tiny shared chunks are folded into their importers; the
route boundaries stay lazy).

Tests run in `src/test/jsdom-environment.ts`, a jsdom environment that keeps Node's
`AbortController`/`AbortSignal`; React Router builds a `Request` for lazy routes and Node's
`Request` rejects jsdom's signal. Pure-Node tests (`jcs`, schema drift) opt into
`// @vitest-environment node`.

## Folder layout

```text
src/
├── main.tsx                  bootstrap: optional MSW worker, router, providers
├── app/                      App (providers), routes (lazy route modules), query client
├── api/                      client.ts, errors.ts, generated/schema.d.ts (+ drift test)
├── components/
│   ├── board-svg/            BoardSvg, BoardLegend, geometry.ts (viewBox, flip, descriptions)
│   ├── layout/               AppShell (skip link, header + SignOutButton, nav, main, footer), NotFoundPage
│   └── ui/                   PageSkeleton, ApiErrorBanner (+ api-error-copy), FieldError, field-errors
├── features/
│   ├── auth/                 token-store.ts (token + tab user id), useMe.ts (GET /v1/me), useCurrentUserId.ts,
│   │                         signed-in-user.ts (owner + foreign-draft purge), useAuthenticate.ts, sign-out.ts
│   ├── boards/               useBoards.ts (GET /v1/boards, board label)
│   ├── onboarding/           OnboardingPage, steps.ts (resume logic), goals.ts, steps/*, progress, mutations
│   ├── explore/              ExplorePage, RouteDetailPage, RouteFilters, RouteList, filters.ts, useRoutes.ts
│   ├── session/              SessionPage, SessionForm, Lineup, session-params.ts, useRecommendations.ts
│   ├── decision/             DecisionPage, reason-codes.ts, ReasonList, LineupNotices, useDecision.ts
│   ├── attempts/             AttemptsPage, AttemptRow, AttemptFormPage, AttemptForm, attempt-form.ts,
│   │                         draft-store.ts (IndexedDB + memory), attempt-sync.ts, useAttempts.ts
├── lib/                      jcs.ts (RFC 8785 + SHA-256), format.ts (local date-time)
├── mocks/                    handlers.ts, state.ts, catalog.ts, recommend.ts, browser.ts, server.ts
├── styles/                   tokens.css (from docs/ux/design-tokens.md), global.css
└── test/                     setup.ts, axe.ts, render.tsx, jsdom-environment.ts
```

Design direction is the "Topo Field Guide" from `docs/ux/design-tokens.md`: paper surfaces,
ink actions, one semantic accent, stamps and hard-offset sheets. Fonts are the documented
fallback stacks (Iowan Old Style/Georgia for display, system sans for UI); the Fraunces and
IBM Plex Sans WOFF2 subsets are not bundled yet.
