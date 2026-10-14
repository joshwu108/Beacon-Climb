# Wireframe: Onboarding (sign in, board, angle, level, goals, logging consent)

Contract basis: `contracts/openapi.yaml` 1.0.0 (CONTRACT-v1 pending tag). PRD 3.1 Onboarding, US-01, US-08; 06 sections 6; ADR-0005. Tokens: `../design-tokens.md`. Journey: `../journeys.md` J1.

## 1. Purpose

Get a person from "nothing" to a working, compatible route search with a clear, revocable choice about attempt logging. Setup collects only board, angle, approximate level, goals and an opt-in consent. No body measurements or private physical metrics are asked (US-01). Climbing frequency (PRD 3.1) and personal-history import are not in CONTRACT-v1 and are therefore not shown (see needs contract change).

This screen has no board SVG: `GET /v1/boards` returns no hold geometry. It does show the synthetic-board stamp when `Board.synthetic` is true.

## 2. Wireframe, mobile (320-480px), one step per view

Route `/onboarding/:step` (URL state: step is shareable and survives reload; answers live in a form store until Step 6 saves).

```
Step 0  Sign in                        Step 1  Board
+--------------------------------+     +--------------------------------+
| BEACON CLIMB     [SYNTHETIC]   |     | BEACON CLIMB                   |
+--------------------------------+     | Step 1 of 6  o o . . . .       |
| Sign in                        |     +--------------------------------+
|                                |     | Which board do you climb on?   |
| Username   [_______________]   |     |                                |
| Password   [_______________]   |     | ( ) Kilter  layout 1  size 10  |
|            [ ] Show password   |     | (o) Synthetic demo             |
|                                |     |     layout 1 - 12x12           |
| [ Sign in ]                    |     |     [SYNTHETIC DEMO BOARD]     |
|                                |     |     Practice routes made by    |
| ---- or ----                   |     |     a generator, not real      |
| [ Try the demo account ]       |     |     climbs.                    |
|  demo build only               |     |                                |
|                                |     | [ Back ]        [ Continue ]   |
| No account creation yet.       |     +--------------------------------+
+--------------------------------+

Step 2  Angle                          Step 3  Level
| Wall angle (degrees)           |     | Approximate level              |
| Choices the board offers:      |     | (o) V0-V2   ( ) V2-V4          |
| ( )30 ( )35 (o)40 ( )45 ...    |     | ( ) V4-V6   ( ) ...            |
| Not sure what yours is?        |     | Used to start your first picks |
|  Use the angle printed on or   |     | until you log attempts.        |
|  beside your board.            |     | [ Back ]        [ Continue ]   |

Step 4  Goals                          Step 5  Logging (optional)
| What do you want from sessions?|     | Save my attempts          [off]|
| [x] Technique  [ ] Strength    |     | If on, Beacon stores each      |
| [ ] Endurance  [x] Project     |     | attempt you log: route, time,  |
| Pick one or more.              |     | sent or not, attempt count,    |
| [ Back ]        [ Continue ]   |     | optional effort and notes,     |
                                       | under your username. Used for  |
Step 6  Review                         | your history and to avoid      |
| Board  Synthetic demo          |     | repeating routes too soon.     |
| Angle  40 deg      [Change]    |     | Turn it off any time in        |
| Level  V2-V4       [Change]    |     | Account > Logging.             |
| Goals  Technique, Project      |     | You can skip this; Explore and |
| Logging  Off       [Change]    |     | sessions still work.           |
| [ Back ]  [ Start exploring ]  |     | [ Back ]        [ Continue ]   |
```

Consent control is a real checkbox/switch with a visible text state ("Off"/"On"), default Off, never pre-selected, never bundled with another control. A "Skip" link is not needed because Off is a valid answer.

Account > Logging (reachable later from the header menu) is a small sheet reusing Step 5 copy with `Consent` status: "On since <consented_at>" / "Off (turned off <revoked_at>)" and the toggle. Turning it off opens a confirm dialog before `DELETE /v1/me/consent`.

### >= 1024px

Two columns inside `--size-content-max`: left column is the step form (max `--size-reading-max`), right column is an `aside` "Your setup so far" summary list (live, `aria-live="polite"` off; summary is static text that updates on step save, not announced). Step nav becomes a vertical `ol`. No other layout change.

## 3. Fields and contract sources

| UI element | Contract source | Direction | Notes |
|---|---|---|---|
| Username, Password | `POST /v1/auth/session` body `username`, `password` | write | minLength 1 each; never logged or echoed |
| Session token, expiry | `POST /v1/auth/session` 201 `token`, `expires_at` | read | `expires_at` drives the "session ending" prompt; token handling per ADR-0005 |
| Try the demo account | same endpoint with the seeded demo user (06 section 12) | write | button rendered only when the build flag says demo; credentials never committed under `apps/web`, injected by the local seed setup |
| Resume routing | `GET /v1/me` `preferences` (null -> start at Step 1), `consent` (null or `revoked_at` set -> logging Off) | read | |
| Board options | `GET /v1/boards` `items[].board_id`, `provider`, `layout_id`, `size_id`, `synthetic` | read | Label is composed in the UI: provider, "layout" + `layout_id`, `size_id`. No display name in contract |
| Synthetic stamp | `Board.synthetic` | read | text stamp "SYNTHETIC DEMO BOARD" when true |
| Angle | `Preferences.angle_deg` (integer) via `PUT /v1/me/preferences` | write | choices should come from the selected board; see needs contract change |
| Level | `Preferences.initial_grade_band` (string, example `"V2-V4"`) | write | allowed values are not specified |
| Goals | `Preferences.goals[]` (strings, example `["TECHNIQUE"]`) | write | UI offers the four values that exist in `RecommendationRequest.goal`: TECHNIQUE, STRENGTH, ENDURANCE, PROJECT |
| Board in `Preferences` | `Preferences.board_id` | write | from Step 1 |
| Logging consent on | `POST /v1/me/consent` body `consent_version` | write | example value `"v1"`; 204 |
| Logging consent off | `DELETE /v1/me/consent` | write | 204 |
| Consent status | `GET /v1/me` `consent.version`, `consent.consented_at`, `consent.revoked_at` | read | |
| Request id in error details | `ErrorEnvelope.request_id` | read | shown in a "Details" disclosure for support |

Save sequence at Step 6 (UI contract, no new endpoints): `PUT /v1/me/preferences` first; then, only if the checkbox is On, `POST /v1/me/consent`. If the second call fails, preferences stay saved and the user lands in Explore with a banner "Logging is still Off. Try again in Account > Logging." (no silent retry, no data loss).

## 4. State matrix

States apply to the step that makes the call: Step 0 (`createSession`), Step 1 (`listBoards`), Step 6 (`putPreferences`, `createConsent`). Copy is final-draft English, plain, no outcome claims.

| State | What is shown | What is actionable | Copy |
|---|---|---|---|
| Loading | Step 0: button shows spinner and "Signing in", fields stay visible (not disabled-looking). Step 1: three skeleton rows with fixed height (no layout shift). Step 6: button "Saving" with `aria-busy` | Step 1: Back. Step 6: nothing double-submittable (button inert while busy) | Step 1 live region: "Loading boards". Step 6: "Saving your setup" |
| Empty | Step 1 when `listBoards` returns `items: []` | "Retry", "Sign out" | "No boards are available yet. Beacon can't set up routes without one. Try again in a moment." |
| Error 4xx | 401 at Step 0: error summary above form, focus moved there. 401 elsewhere (token expired): modal "Sign in again" preserving answers in the form store. 403 on `createConsent`: banner. 404: n/a (no id-based routes on this screen) | Edit credentials / Sign in again / Retry | 401: "Username or password not recognised." (same text for unknown user and wrong password). Expired: "Your session ended. Sign in again to keep going; your answers are kept." |
| Error 5xx / network | Inline error panel in the step with request id disclosure | Retry; Back remains | "Something went wrong on our side. Your answers are still here. Try again." |
| Validation 422 | `field_errors[].field` mapped to the step field (`board_id`, `angle_deg`, `initial_grade_band`, `goals`); if `field_errors` is empty (as in the current fixtures) show a form-level message and keep all values | Fix field and resubmit; error summary links jump to field | Field: `message` from the envelope if present; fallback "Check this answer and try again." Form-level fallback: "We couldn't save these choices. Check them and try again." |
| Conflict 409 | Not defined for any call on this screen | n/a | n/a (no 409 in openapi for these operations) |
| Insufficient data | Not applicable to setup itself. Step 3 helper text sets expectations for later cold start | n/a | Step 3: "Until you log attempts, session picks are based on this level, your goals and each route's grade. If you opt in to logging, your attempts are also used so Beacon can avoid repeating routes too soon." |
| Offline | Banner at top (warning tone, text label): setup cannot be saved offline; entered answers stay in the form store; Continue between steps still works; save button disabled with reason text | "Retry when online" (auto-detects, announced politely) | "You're offline. You can keep filling this in, but it can't be saved until you're back online." |

Rate limiting: ADR-0005 states 10 login attempts per 15 minutes; `createSession` has no 429 response in openapi (needs contract change). Until defined, a 429 is treated as 5xx/network with retry copy plus the `Retry-After` header if present.

## 5. Accessibility

### Landmarks and headings

| Landmark | Content |
|---|---|
| `header` (banner) | Brand, synthetic stamp (text), theme-neutral |
| `nav aria-label="Setup progress"` | `ol` of steps; current step `aria-current="step"`; completed steps are links to go back (steps not yet reached are plain text) |
| `main` | The step form |
| `footer aria-label="Privacy and data"` | One line: "Pseudonymous username only. No body measurements are collected." plus link to privacy note (BC-0007 doc, when it exists) |

Heading outline: `h1` "Set up Beacon Climb" (visually reduced on steps after 0 but present); `h2` = step title ("Which board do you climb on?"); error summary is an `h2` "There is a problem" only when present. Each step uses `fieldset` + `legend` for radio and checkbox groups.

### Keyboard path

1. Skip link "Skip to main content".
2. Header (no tab stops except the account menu once signed in).
3. Progress nav: tab stops only on completed-step links.
4. `main`: step heading receives programmatic focus (`tabindex="-1"`) on every step change and the new step is announced ("Step 2 of 6, Wall angle").
5. Fields in DOM order. Radio groups: one tab stop, arrow keys move the choice, Space selects. Checkbox group: Tab per box, Space toggles.
6. Back, then Continue. Enter inside a text field or radio group submits the step; Esc does nothing on steps (no modal) except closing the confirm dialog of Account > Logging.
7. After a failed submit: focus goes to the error summary (`role="alert"`), links jump to the offending field, fields carry `aria-invalid="true"` and `aria-describedby` to their message.
8. The "demo account" button is a normal button, after the Sign in button in tab order.

Password field: `autocomplete="username"` / `current-password`, show-password is a labelled checkbox.

### Focus and motion

- `:focus-visible` uses the two-tone ring from tokens on every control; radio and checkbox custom controls keep a visible ring on the control and label.
- Step changes use a 150 ms opacity crossfade; with `prefers-reduced-motion: reduce` the change is instant and nothing slides.
- Touch targets >= 44px including each radio row (whole row is the label).
- Consent switch must work without drag gestures (checkbox semantics).

## 6. Needs contract change (this screen)

1. Account creation: US-01 says "create a pseudonymous account"; CONTRACT-v1 has only `POST /v1/auth/session` (ADR-0005: local demo accounts). Decide whether a registration endpoint is post-v1; UI shows "No account creation yet" meanwhile.
2. Available angles per board: `Board` has no `available_angles` (or per-board angle range). Interim: free numeric entry validated by a `listRoutes` call returning at least one item.
3. Allowed `initial_grade_band` values and their relation to `grade_label` (the example `"V2-V4"` is the only evidence). Needs an enum or a statement that it is free text, plus the grade table from BC-0014.
4. Goal vocabulary mismatch: PRD 3.1 names warm-up, technique, project, exploration; openapi `RecommendationRequest.goal` enum is TECHNIQUE, STRENGTH, ENDURANCE, PROJECT, and `Preferences.goals` is an untyped string array. UI follows openapi; confirm that `Preferences.goals` uses the same enum.
5. Climbing frequency and optional history import (PRD 3.1) have no fields/endpoints; not shown.
6. Consent text and version: nothing tells the client the current required `consent_version` or the legal text. Interim: the UI bundles the text and a constant that must equal what the server accepts (`"v1"` in fixtures).
7. Consent revocation semantics: what happens to already-saved attempts after `DELETE /v1/me/consent` is not in the contract (see BC-0007 retention doc). The 403 error code for attempts without consent is `CONSENT_REQUIRED` in 06 section 7 but openapi shows only a bare 403.
8. `POST /v1/auth/session` 429 (ADR-0005 rate limit) is not declared.
9. `GET /v1/me` has no `role`; not needed here but needed by Observatory (see `observatory-stub.md`).
10. Whether `POST /v1/me/sessions/recommendations` is allowed when consent is absent (openapi lists 403 without saying why); determines whether onboarding can promise "Explore and sessions still work" (copy in Step 5 assumes yes).
