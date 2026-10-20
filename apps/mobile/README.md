# Beacon Climb mobile (`apps/mobile`)

React Native (Expo SDK 58, expo-router) version of the Beacon Climb journey, built to be demoed
on a laptop: it runs in the browser through Expo's web target (react-native-web), framed as a
390 x 844 phone on wide screens, and in the iOS Simulator through Expo Go. `apps/web` is
unchanged; this app reuses its contract types, pure helpers and MSW mocks as source.

## Demo

From the repo root, after `pnpm install`:

| Mode                    | Command                                                  | Needs          |
| ----------------------- | -------------------------------------------------------- | -------------- |
| Mock data (backend off) | `make mobile`                                            | nothing else   |
| Real local stack        | `make dev && make seed-demo`, then `make mobile MOCKS=0` | Docker, `.env` |

Open <http://127.0.0.1:8081>. In mock mode, register any username (3 to 32 lowercase letters,
digits or `_`) and an 8+ character password; in real-stack mode you can also sign in as
`demo` / `demo-password`. Choose the synthetic demo board (`synthetic-demo-1`) and an angle.

For model-backed recommendations and "More like this" on the real stack, start the ML service
too (`make ml-up MODEL=model-a-gbm-20261019`); without route embeddings the similar-routes
endpoint answers with no items and the app says so.

iOS Simulator (Xcode installed): `cd apps/mobile && EXPO_PUBLIC_USE_MOCKS=true pnpm ios`
(Expo Go). Against the stack, the simulator calls `EXPO_PUBLIC_API_BASE_URL`, default
`http://127.0.0.1:3000`.

Keep the demo on your own screen: the Kilter data is licensed for local research only, so do
not deploy this app or share screenshots of real Kilter routes. Mocks and tests use only the
synthetic board.

## What is in it

- Onboarding: account (register or sign in), consent (versioned text, nothing pre-ticked),
  board and angle, starting band and goals. Resumes at the first unfinished step.
- Session tab: goal and route count, heuristic-v2 by default with a debug-only heuristic-v1
  toggle (dev builds or `EXPO_PUBLIC_DEBUG=true`). The first pick is a hero card with the
  board, grade and the reason codes from the decision record; later picks are rows. Swap,
  dismiss, full decision record. Logging an attempt refreshes the lineup on return.
- Log attempt: sent / not yet, attempts, optional 1-10 effort, progress, when, notes. One
  Idempotency-Key per form. Corrections use PATCH with If-Match.
- Explore tab (angle chips, optional grade range, cursor paging), route detail with the board,
  legend, evidence and tags, and "More like this" (`GET /v1/routes/{id}/similar`).
- Log tab: attempt history with correct and retract.
- Scan tab: a placeholder for E07 board photo upload. No camera code, no upload endpoint.

## How it is wired

- API: `openapi-fetch` over `src/api/generated/schema.d.ts`, generated from
  `contracts/openapi.yaml` (1.3.0) with openapi-typescript 7.13.0 (`pnpm generate:api`); the
  drift test fails when the committed file differs from a fresh run.
- Base URL (`src/api/config.ts`): mock mode answers in-process; web uses its own origin and
  the Metro dev proxy (`dev-proxy.js`) forwards `/v1` and `/health` to
  `BEACON_API_PROXY_TARGET` (default `http://127.0.0.1:3000`). The API's CORS allow-list is
  unchanged (a direct 8081 -> 3000 call is refused); native builds use
  `EXPO_PUBLIC_API_BASE_URL`.
- Token: expo-secure-store on iOS / Android, localStorage on web (`src/auth`).
- Mock mode (`EXPO_PUBLIC_USE_MOCKS=true`): `src/mocks/mock-fetch.ts` runs apps/web's MSW
  handlers through msw's `getResponse`, so no service worker is needed and the same mocks work
  on native. Additions kept here, not in apps/web: a similar-routes handler (the committed
  fixture, plus a labelled stand-in distance `mock-hold-overlap` for other routes) and an echo
  of the requested `policy_version` (the shared mock predates 1.3.0).
- Shared with apps/web as source through the `@web/*` alias: error types and copy, board
  geometry, reason-code copy, goals, filters, grade and attempt-form helpers, mocks.
- Theme: `src/theme/theme.ts` holds every token (cream "Topo Field Guide" palette from
  `docs/ux/design-tokens.md`, light and dark; Young Serif + Atkinson Hyperlegible Next; 4 pt
  spacing; 44 pt targets; hard paper shadows).
- Dependencies are exact pins no newer than 2026-10-06; `pnpm-workspace.yaml` overrides hold
  the Expo / React Native transitive packages to the same cutoff.

## Tests

`make test-mobile` runs, in `apps/mobile`:

- `pnpm lint`: `tsc --noEmit` (strict) and Prettier.
- `pnpm test`: Jest (jest-expo) with @testing-library/react-native: token store, API config,
  schema drift, mocks, session store and request body, onboarding resume logic, board
  accessibility, the dev proxy, and router tests for onboarding and the session builder.
- `pnpm test:e2e`: Playwright smoke test on the exported web build in mock mode, at 1280
  (phone frame) and 390 wide.
