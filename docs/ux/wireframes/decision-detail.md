# Wireframe: Decision detail ("Why this route?")

Contract basis: `GET /v1/me/decisions/{decision_id}` (`getDecision`, schema `Decision`), `GET /v1/routes/{route_variant_id}`; PRD 3.1 Why This Route?, 8.2, 9.1, US-05; 06 sections 9-10; ADR-0003 (ledger). Fixture: `contracts/examples/getDecision/response.json`. Journey: `../journeys.md` J5.

## 1. Purpose

Show, for one recommendation list, which route was ranked where and why, using only what the API returns: reason codes, policy and model versions, the time of the history snapshot, and the cold-start and fallback flags. It says when there was not enough history and what the list was based on instead. It never predicts what the climber will do, and it does not show a chance of success.

The public `Decision` object does not include the internal feature snapshot, candidate list or tie-break seed (06 section 10: "never the snapshot URI"; PRD 8.2: the snapshot holds more than the API exposes), so the "evidence" shown here is limited to reason codes and the freshness time. That limit is stated on screen and listed under needs contract change.

## 2. Wireframe, mobile (320-480px)

Route `/decisions/:decision_id?position=<n>`.

```
+------------------------------------------+
| BEACON CLIMB   [Synthetic demo . 35 deg] |
+------------------------------------------+
| < Back to session                        |
| Why these routes                    (h1) |
| Decision 018f...0003  [Copy id]          |
| [STARTING PICKS]  (if cold_start)        |
| Not enough logged attempts yet, so this  |
| list was built from grade, angle and     |
| your goal instead of your history.       |
| [!] SIMPLIFIED RECOMMENDATION            |  fallback_used
+------------------------------------------+
| Why Synthetic Traverse 1  (position 1)  |
|  +------------------------------------+  |
|  | board           [RECOMMENDED]      |  |
|  |   (F)<>   .:(H):.   [F]            |  |
|  |  ((S))                              |  |
|  +------------------------------------+  |
| V2 . Community grade . 0 observations    |
| Reasons                             (h3) |
|  * Matches your board and angle          |
|    It exists on your board at your angle.|
|  * Grade fits your goal                  |
|    Its grade is close to the target...   |
| Ordering value 0.72                      |
|   (relative order only, not a chance     |
|    of success)                           |
| Success estimate: not provided in this   |
|   version                                |
| [NOT ENOUGH HISTORY]                     |
| [ Log an attempt on this route ]         |
+------------------------------------------+
| All routes in this list             (h2) |
| 1 Synthetic Traverse 1   V2   (viewing)  |
| 2 Synthetic Crimp Line   V3  [View]      |
+------------------------------------------+
| About this list                     (h2) |  details/aside
| Policy: heuristic-v1                     |
| Model: none (no learned model in use)    |
| Based on your history up to Oct 14 13:59 |
| [ Open in Replay Lab ]                   |
+------------------------------------------+
| nav ... footer                           |
+------------------------------------------+
```

### >= 1024px

Two columns: left = selected-route explanation with the large board, right = `aside` "About this list" (versions, freshness, id) pinned, and "All routes in this list" under the board. Hold callout beside the hold. Nav rail on the left.

## 3. Fields and contract sources

| UI element | Contract source | Notes |
|---|---|---|
| Page id, copy button | path `decision_id`, `Decision.decision_id` | shown shortened with the full id in an accessible name |
| Starting-picks banner | `Decision.cold_start` | true -> banner with the copy in the matrix |
| Simplified banner | `Decision.fallback_used` | true -> warning banner (same copy as Session Builder) |
| Freshness line | `Decision.feature_as_of` | "Based on your history up to <local time>" |
| Policy | `Decision.policy_version` | printed as given (e.g. `heuristic-v1`) |
| Model | `Decision.model_version` | `"none"` printed as "No learned model in use"; other values printed as given |
| Routes in list | `Decision.recommendations[]` | ordered by `position` |
| Row/selection | `recommendations[].route_variant_id`, `position` | selection mirrored in URL `position=` |
| Route name, grade, observations, tags | `GET /v1/routes/{route_variant_id}` `name`, `grade_label`, `grade_numeric`, `grade_observation_count`, `tags`, `missing_tags` | `Recommendation` has no route details |
| Board holds | `GET /v1/routes/{route_variant_id}` `placements[].hold_id`, `x`, `y`, `role` | |
| Reasons | `recommendations[].reason_codes[]` | chip text and long text from the copy table in `session-builder.md` (same table, not redefined) |
| Ordering value | `recommendations[].rank_score` | labelled "Ordering value (relative; not a chance of success)"; shown for the selected route only |
| Uncertainty stamp | `recommendations[].uncertainty_label` | `INSUFFICIENT_HISTORY` -> stamp "NOT ENOUGH HISTORY"; other values printed as text |
| Success estimate line | `recommendations[].predicted_send_probability` | null in v1 -> line "Success estimate: not provided in this version". A non-null value is not rendered until a design review defines wording and calibration evidence |
| Actions | `route_variant_id` -> Attempt Log; `decision_id` -> Replay Lab | navigation only |
| Synthetic stamp | `GET /v1/boards` `synthetic` for the route's `board_id` | |

Not in the contract and therefore not shown: feature values (attempts in the last 7/30/90 days, send rates), candidate list, target grade, weights, tie-break seed, decision timestamp, request settings (goal, max_routes) used for the request.

## 4. Board and hold states on this screen

| State | Shape | Pattern | Text channel | Color (redundant) |
|---|---|---|---|---|
| START / HAND / FOOT / FINISH | ringed circle / circle / hatched small square / diamond with bar | solid / solid / hatch / solid | START and FINISH labels always; others on focus/pin | `--color-hold-*` |
| Selected (pinned) | 3px outer ring + gap + check badge | ring | callout: "Hold h1, start, lower left. Selected."; `aria-pressed` | ink |
| Recommended | four accent rays on every hold of the route | solid rays (2 pulses, then static) | stamp "RECOMMENDED" (or "SIMPLIFIED RECOMMENDATION" when `fallback_used`) and the position number | `--color-accent` |
| Insufficient data | dotted outlines, hatch fill, "?" badge | dotted | stamp "NOT ENOUGH DATA" when the route's `grade_observation_count == 0` or `grade_numeric == null`. The decision-level label from `uncertainty_label` is a separate text stamp ("NOT ENOUGH HISTORY") and does not change glyph styling | neutral |

Every state above is also stated in words in the explanation text, so the board is never the only place a fact appears.

## 5. State matrix

| State | What is shown | What is actionable | Copy |
|---|---|---|---|
| Loading | Skeleton for header block, board frame and 3 reason lines at final height. Per-route details load after the decision: list rows show name placeholders | Back link, nav | Live: "Loading this decision" |
| Empty | `recommendations: []`: header and versions render; list section shows the message | "Build a new session" | "This decision didn't list any routes." |
| Error 4xx | 404 (also returned for someone else's decision by design): full-page notice. 401: sign-in dialog. Malformed id: same as 404 (checked client-side). Route-level 404 for a listed route: that row shows "Route no longer available" but keeps its reason text | "Back to session", "Build a new session", Sign in again | 404: "We couldn't find this decision. The link may be old or it isn't yours." Row: "This route is no longer available. The reasons below are from when it was recommended." |
| Error 5xx / network | Panel in the failed zone (decision or route details), request id in "Details" | "Try again" | "This decision couldn't be loaded. Try again." |
| Validation 422 | Not defined for this endpoint (only 404 in openapi); invalid ids handled as 404 | n/a | n/a |
| Conflict 409 | Not applicable (read-only) | n/a | n/a |
| Insufficient data | `cold_start: true` banner and `uncertainty_label` stamp; reasons still listed; success-estimate line says not provided | Everything works | "Starting picks. Beacon didn't have enough of your logged attempts for history-based picks, so this list is based on each route's grade, your angle and your goal. It doesn't estimate your chance of sending." |
| Offline | A decision already opened is cached (it is immutable), shown with "SAVED COPY"; route details not yet cached show placeholders; uncached decision shows offline notice | Read cached content, nav, Log an attempt (draft mode) | "You're offline. Showing a saved copy of this decision." / "This decision isn't saved on this device. Connect to open it." |

## 6. Accessibility

### Landmarks and headings

| Landmark | Content |
|---|---|
| `header` | brand, board/angle chip, synthetic stamp |
| `nav aria-label="Primary"` | Explore, Session, Log, More |
| `main` | page content |
| `section aria-labelledby` (selected route) | `h2` "Why <route name>", board, reasons |
| `section aria-labelledby` (list) | `h2` "All routes in this list", `ol` |
| `aside aria-label="About this list"` | versions, freshness, id, Replay Lab link |
| `footer aria-label="Data and privacy"` | data note |

Heading outline: `h1` Why these routes; `h2` Why <route name>; `h3` Reasons; `h2` All routes in this list; `h2` About this list. Banners are `role="status"` text, not headings. Reasons are a `ul` with chip text as the `strong` lead and the long text as the item body.

### Keyboard path

Tab order: skip to main -> nav -> Back to session -> Copy id -> Board|List toggle -> zoom controls -> Labels -> Skip board -> **board (roving)** -> Log an attempt -> each row in "All routes in this list" ("View" button, `aria-current` on the one shown) -> About-this-list disclosure (mobile) -> Open in Replay Lab.

- Board keys: arrows move hold focus to the nearest hold in that direction; Enter or Space toggles the pinned selection; Esc closes the callout then exits to the board frame; Home/End first/last hold; +/-/0 zoom.
- Choosing another route in the list moves focus to the `h2` of the explanation and the live region announces "Showing position 2, Synthetic Crimp Line".
- "Copy id" announces "Decision id copied" in a live region.
- List view of the board is a table (Hold, Role, Position) equal in function to the SVG.

### Focus and motion

- Two-tone `:focus-visible` ring on all controls and holds.
- Beacon pulse and clip-path reveal removed under `prefers-reduced-motion: reduce`; the rays remain static.
- Reasons are real text in a list, reachable by screen-reader browse mode without touching the board.
- Text blocks limited to `--measure` (62ch); `rank_score` is announced as "Ordering value 0.72, relative order only".

## 7. Needs contract change (this screen)

1. `Decision` exposes only reason codes: US-05 asks for "which evidence drove a route recommendation" and PRD 3.1 for a "snapshot of features, evidence". Proposed: per-recommendation `evidence` (for example target grade, grade used, observation count, recency signal) and the feature values behind `GOAL_CHALLENGE_MATCH`, `NOVEL_ROUTE`, `WELL_OBSERVED_GRADE`, with the same fields the ledger records, minus the snapshot URI.
2. `Decision` has no `created_at` (only `feature_as_of`), no request context (`goal`, `board_id`, `angle_deg`, `max_routes`, `session_id`) and no `exclude` list, so "this list was for goal TECHNIQUE" can't be shown.
3. No collection read for decisions: the UI only reaches a decision from a lineup or from a locally stored recent-ids list.
4. `Recommendation` has no route name or grade (see Session Builder); a route summary embedded in `Decision.recommendations[]` would remove the N+1 calls.
5. Reason-code enum and `uncertainty_label` values (see Session Builder). Also the meaning and display of `rank_score` (range, comparability across decisions).
6. `model_version: "none"` semantics and display names for later versions.
7. 404 vs 422 behaviour for malformed `decision_id` (openapi path param is `format: uuid`; no 422 response declared).
8. Erased users: PRD 9.1 `REPLAY_UNAVAILABLE_ERASED` is not in the contract; the decision itself will 404 once the user is gone.
