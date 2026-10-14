# Wireframe: Today / Session Builder

Contract basis: `POST /v1/me/sessions/recommendations` (`createRecommendations`), `GET /v1/routes/{route_variant_id}`, `GET /v1/me`, `GET /v1/me/attempts`; `RecommendationRequest`, `RecommendationResponse`, `Recommendation` schemas in `contracts/openapi.yaml` 1.0.0; PRD 3.1 Today/Session Builder, 4.2, 4.3, 8.2, US-04; 06 sections 9-10. Journey: `../journeys.md` J3.

Naming note: this "session" is a climbing session on the client side (`session_id` is a UUID the client generates and sends). It is unrelated to `POST /v1/auth/session` (sign in).

## 1. Purpose

Let a climber pick a goal and a number of routes, then show an ordered lineup (3 to 6 by default) with plain-language reasons, honest labels when history is thin ("starting picks") or when the system used its fallback ("simplified recommendation"), and a way to dismiss or swap routes. It describes why routes were chosen. It never states or implies what the climber will manage to do.

## 2. Wireframe, mobile (320-480px)

Route `/session` (goal, count and current `session_id` kept in URL state; lineup is client state keyed by `decision_id`).

```
+------------------------------------------+
| BEACON CLIMB   [Synthetic demo . 35 deg] |
+------------------------------------------+
| Build a session                     (h1) |
| Goal                         (fieldset)  |
| (o) Technique ( ) Strength               |
| ( ) Endurance ( ) Project                |
| Routes  [ - ]  4  [ + ]   (3 to 6)       |
| [ Build my session ]                     |
+------------------------------------------+
| STARTING PICKS (banner, neutral)         |  cold_start == true
| Not enough logged attempts yet for       |
| history-based picks. These come from     |
| grade, angle and your goal. Logging      |
| attempts adds your history to the mix.   |
|                                          |
| [!] SIMPLIFIED RECOMMENDATION (warning)  |  fallback_used == true
| The full ranking step wasn't available,  |
| so this list uses a simpler fallback.    |
| No chance-of-success figures are shown.  |
+------------------------------------------+
| Your session: 4 routes              (h2) |
| Picks use your history up to 13:59       |  feature_as_of
| [Refresh picks]                          |
| +--------------------------------------+ |
| |  board (selected card's route)       | |
| |      (F)<> FINISH     [RECOMMENDED]  | |  accent rays + stamp
| |   .:(H):.   .:[F]:.                  | |  .:x:. = accent rays
| |  ((S)) START                         | |
| +--------------------------------------+ |
| 1  Synthetic Traverse 1        V2        |
|    [Matches board + angle]               |
|    [Grade fits your goal]                |
|    [Show on board] Why this route >      |
|    [ Start ]  [ Not for me ]             |
| 2  Synthetic Crimp Line        V3        |
|    ... same card ...                     |
| 3 ...                                    |
| 4 ...                                    |
+------------------------------------------+
| [Explore][Session][Log][More]            |
+------------------------------------------+
```

Cards are an ordered list (`ol`, position from `position`). One primary button per card ("Start"); "Why this route" is a text link with accent underline (accent is reserved for recommendation items, tokens section 2).

### >= 1024px

Two columns: left = setup + lineup (22rem, scrolls), right = large board for the selected card, with the hold callout beside the hold. The `aside` shows "About this lineup" (policy and freshness details as plain text; the same data as Decision detail's header). Nav rail on the left.

## 3. Fields and contract sources

Request (`POST /v1/me/sessions/recommendations`, all fields required by `RecommendationRequest`):

| UI element | Contract field | Source / rule |
|---|---|---|
| Board (implicit) | `board_id` | `GET /v1/me` `preferences.board_id` |
| Angle (implicit) | `angle_deg` | `GET /v1/me` `preferences.angle_deg` |
| Goal radios | `goal` enum TECHNIQUE, STRENGTH, ENDURANCE, PROJECT | default = first of `preferences.goals[]` if it is one of the four |
| Routes stepper | `max_routes` (integer, openapi 1-20) | UI limits to 3-6 per PRD 3.1 (narrower than contract; flagged) |
| (hidden) session | `session_id` (uuid) | client-generated when the user presses "Start a new session"; reused for refresh and swaps within the session |
| Dismissed routes | `exclude_route_variant_ids[]` | accumulates the `route_variant_id`s the user dismissed in this session; sent as `[]` initially |

Response and what the UI does with each field:

| UI element | Contract field | Notes |
|---|---|---|
| Decision link | `decision_id` | opens Decision detail (`decision-detail.md`); also saved in the local "recent decisions" list for Replay Lab |
| Card order and number | `recommendations[].position` | render in `position` order |
| Card route name, grade | `GET /v1/routes/{route_variant_id}` `name`, `grade_label`, `grade_numeric` | one request per recommended route (max 6, in parallel, cached); `Recommendation` itself carries no name or grade |
| Reason chips | `recommendations[].reason_codes[]` | mapped through the copy table below; unknown code shown as "Other reason: CODE" |
| Not-enough-history stamp | `recommendations[].uncertainty_label` (`"INSUFFICIENT_HISTORY"` in all fixtures) | stamp "NOT ENOUGH HISTORY"; unknown labels are printed as text |
| Starting-picks banner | `cold_start` | true -> banner above |
| Simplified banner | `fallback_used` | true -> warning banner and stamp on each card |
| Freshness | `feature_as_of` (date-time) | "Picks use your history up to <time>"; adds "Your latest attempt may not be included yet." only when the newest `GET /v1/me/attempts` `received_at` is later than `feature_as_of` |
| Policy detail (aside / Decision detail only) | `policy_version`, `model_version` | not in the lineup body; `model_version: "none"` is shown as "No learned model in use" there |
| Chance of success | `predicted_send_probability` | **Never rendered in v1** (null in all fixtures). If a later contract returns a number, a separate design review is required first |
| Score | `rank_score` | not shown in the lineup (can be misread as a chance); Decision detail shows it with a "relative ordering value" label |
| Logged marker on a card | `GET /v1/me/attempts` `items[].route_variant_id`, `status`, `sent`, `attempts_count` | client-side join over attempts since the session started: "Logged: 2 attempts, sent" |
| Start | navigates to Attempt Log with `route_variant_id` | no request |
| Not for me | client adds to `exclude_route_variant_ids`, then re-POSTs with the same `session_id` | see below |

### Reason-code copy

Source of the vocabulary: 06 section 9 (not enumerated in openapi). Each line explains why the route is in the list; none predicts an outcome.

| Code | Chip text | Longer text (Decision detail) |
|---|---|---|
| `COMPATIBLE_ANGLE` | Matches your board and angle | This route exists on your board at your selected angle. |
| `GOAL_CHALLENGE_MATCH` | Grade fits your goal | Its grade is close to the target grade the policy uses for your goal and level. |
| `NOVEL_ROUTE` | New to you | It isn't among your recent logged routes. |
| `PREFERRED_STYLE` | Matches your goals | It matches a preference taken from your selected goals. |
| `WELL_OBSERVED_GRADE` | Grade is well observed | Its grade is based on a good number of logged observations. |
| `COOLDOWN_SKIPPED` | Recently attempted routes left out | Routes you attempted within the policy's repeat-avoidance window were not considered. (Meaning of this code per route is unclear; see needs contract change.) |
| `LOW_INVENTORY_WIDENED` | Few routes matched | Not many routes fit, so the grade window was widened to fill the list. |
| `COLD_START_HEURISTIC` | Starting pick | Chosen from grade and your goal because there isn't enough logged history yet. |

Show at most three chips per card; "+N more" opens the Decision detail.

### Swap and dismiss behaviour (client-side, no new endpoint)

"Not for me" adds the route to the local exclusion list and calls the same endpoint again with the same `session_id`. The response is a new decision (new `decision_id`) and replaces the lineup; items that are new compared with the previous list get a "NEW" stamp, and a live region says "Replaced route 2. The list was refreshed." The contract has no way to replace a single slot or to tell the server a route was dismissed (needs contract change); the help text says "Dismissed routes are only remembered on this device for this session."

Changing goal, count, or the board/angle in preferences makes the lineup stale: changing board or angle immediately clears the lineup and shows "You changed your board or angle, so the earlier picks no longer apply." (PRD 4.3).

## 4. Board and hold states on this screen

The board shows the route of the currently selected card (default: position 1).

| State | Shape | Pattern | Text channel | Color (redundant) |
|---|---|---|---|---|
| START / HAND / FOOT / FINISH | circle with ring / circle / hatched small square / diamond with bar | solid / solid / hatch / solid | START and FINISH labels always; others on focus, pin or Labels toggle | `--color-hold-*` |
| Selected (pinned hold) | 3px outer ring + 3px gap + check badge | ring | callout "Selected"; `aria-pressed` | ink |
| Recommended | every hold of the route gets four accent rays (N, E, S, W, 6px) | solid rays, 2 pulses then static | corner stamp "RECOMMENDED" and the card's position number | `--color-accent` |
| Insufficient data | dotted outlines, hatch fill, "?" badge | dotted | stamp "NOT ENOUGH DATA" when the displayed route has `grade_observation_count == 0` or `grade_numeric == null`; "NOT ENOUGH HISTORY" label comes from `uncertainty_label` and is a card stamp, not a hold change | neutral |
| Simplified recommendation | no hold change | | stamp "SIMPLIFIED RECOMMENDATION" on the board corner replaces "RECOMMENDED" | warning (redundant) |

When `fallback_used` is true the rays stay (the routes are still Beacon's suggestions) but do not pulse, and the corner stamp text changes, so the difference is carried by text and motion rather than color.

## 5. State matrix

| State | What is shown | What is actionable | Copy |
|---|---|---|---|
| Loading | Setup form stays; skeleton cards equal to `max_routes`; button reads "Building" with `aria-busy`; board skeleton | Goal/count editable only after load (controls inert while request is pending to avoid double requests) | Live: "Building your session". Route details loading: card shows name placeholder at final height |
| Empty | `recommendations: []` | "Change goal", "Explore routes" | "No routes fit this goal on your board and angle right now. Try another goal or browse all routes." (reason codes unavailable) |
| Error 4xx | 401: sign-in dialog preserving the form. 403: panel "Can't build a session" (reason not specified by contract). 404: none for this call | Sign in again; "Check setup" link to Onboarding | 401: "Your session ended. Sign in again to build your session." 403: "This account can't build sessions right now." |
| Error 5xx / network | Panel above the lineup; the previous lineup (if any) stays visible but dimmed with a "Not refreshed" stamp. Note: a 200 with `fallback_used: true` is not an error and has its own banner | "Try again" (manual; no automatic retry loop) | 503: "We couldn't build your session. Try again in a moment." |
| Validation 422 | Panel with request id disclosure; `field_errors` mapped to Goal or Routes where present (fixture has an empty array) | "Check setup", edit and retry | "Beacon couldn't use these settings. Check your board, angle and goal." |
| Conflict 409 | Not defined for this endpoint | n/a | n/a |
| Insufficient data | `cold_start: true` -> "Starting picks" banner; each card carries "NOT ENOUGH HISTORY" stamp from `uncertainty_label`; no probability anywhere | Everything works; "Log an attempt" link explains it adds history | Banner: "Not enough logged attempts yet for history-based picks. What you get instead: routes ranked by how well their grade fits your goal and level, on your board and angle. Logging attempts adds your history." (No promise about when this changes; the threshold is not in the contract.) |
| Offline | Warning stamp "SAVED COPY" on the last lineup with its `feature_as_of`; "Build" and "Refresh" disabled with reason text; "Start" still works and opens Attempt Log in offline-draft mode | Start, Why this route (if the decision was cached), nav | "You're offline. This is the list from 13:59. You can still log attempts; they'll be saved on this device until you're back online." |

## 6. Accessibility

### Landmarks and headings

| Landmark | Content |
|---|---|
| `header` | brand, board/angle chip, synthetic stamp |
| `nav aria-label="Primary"` | Explore, Session (`aria-current="page"`), Log, More |
| `main` | setup form, banners, lineup |
| `form aria-label="Session setup"` | goal, count, build |
| `section aria-labelledby` (lineup) | `h2` "Your session: N routes", freshness text, board, `ol` |
| `aside aria-label="About this lineup"` | policy/freshness details (right column at >= 1024; collapsed `details` on mobile) |
| `footer aria-label="Data and privacy"` | data note |

Heading outline: `h1` Build a session; `h2` Your session: N routes; `h3` per card = route name; banners use `role="status"` (not headings). `ol` has an `aria-label` "Recommended routes in order".

### Keyboard path

Tab order: skip to main -> nav -> Goal radios (one stop, arrows change) -> Routes minus -> plus -> Build -> (after results) Refresh picks -> Board|List toggle -> zoom controls -> Labels toggle -> Skip board -> **board (roving)** -> for each card in order: Show on board (`aria-pressed`), Why this route, Start, Not for me.

- After Build completes, focus moves to the `h2` of the lineup (`tabindex="-1"`) and a polite live region announces "4 routes ready" plus the banner text when present.
- After "Not for me", focus moves to the heading of the card now at that position; the live region announces the replacement. If the list is shorter, focus goes to the previous card's heading.
- Board keys: arrows move hold focus to the nearest hold in that direction; Enter or Space toggles the pinned selection; Esc closes the callout, then exits to the board frame; Home/End first/last; +/-/0 zoom. Board and List views are equivalent (List = table of Hold, Role, Position).
- "Show on board" updates the SVG without moving focus.

### Focus and motion

- Two-tone `:focus-visible` ring on all controls and holds.
- `lineupRevealTl` (clip-path wipe, 40ms stagger) and `beaconPulseTl` (2 pulses) are removed under `prefers-reduced-motion: reduce`: content appears at final state, rays are static.
- Status messages (banners, replacement notice) are text and live regions, not toasts that disappear; nothing auto-dismisses.
- 44px minimum target for card buttons; "Not for me" requires no confirmation but is reversible through Refresh picks (the exclusion list is visible: "Dismissed: 1 route, Reset").

## 7. Needs contract change (this screen)

1. `Recommendation` has no route name, grade or tags; the UI makes up to six `getRoute` calls. Proposed: embed a `RouteVariantSummary` (or `route` object) in each recommendation.
2. Reason-code vocabulary and `uncertainty_label` values are free strings in openapi; 06 section 9 lists the codes. Needs enums and a statement on whether `COOLDOWN_SKIPPED` is per-route or per-lineup.
3. `max_routes` is 1-20 in openapi and 3-6 in PRD 3.1/4.2. Confirm UI narrowing is intended, and what the server does if it cannot fill `max_routes` (the UI shows fewer without error).
4. Goal vocabulary: PRD names warm-up, technique, project, exploration; openapi names TECHNIQUE, STRENGTH, ENDURANCE, PROJECT. UI follows openapi.
5. "Desired duration" (PRD 4.2 step 2) has no field.
6. No way to replace a single slot or record a dismissal (exposure/feedback); dismissals are local only.
7. `cold_start` is a boolean only: no history count or required minimum, so no progress indicator can be shown. Proposed: `history_attempt_count` and `min_history_for_personalization` (or a documented rule).
8. Consent dependency: whether `createRecommendations` returns 403 when logging consent is off (openapi lists 403 with no code).
9. `COMPATIBLE_ANGLE` and ranking guarantees imply the server filters by board/angle; confirm the 422 conditions (inactive/incompatible `exclude` ids, unknown `board_id`, `max_routes` out of range) and give `field_errors` for them.
10. Serving model/policy copy: `model_version` values other than `"none"` and their display names.
