# Beacon Climb user journeys (BC-0008)

Status: DRAFT for review. Owner: product-designer. Contract basis: `contracts/openapi.yaml` 1.0.0 (CONTRACT-v1; human review and tag pending), fixtures in `contracts/examples/**`, PRD sections 3, 4, 8.2, 8.3, 9.1, `planning/06-first-two-sprints.md` sections 5-10, master plan section 1 (D1-D6), section 6 and 7b, `planning/08-unanswered-questions.md`, `planning/09-prd-critique.md` section 2.

Companion files: `wireframes/*.md` (one per screen) and `design-tokens.md`. Only fields that exist in the contract are used; anything a journey needs that does not exist is listed per journey and aggregated in section 11.

Ground rules that apply to every journey:

- No health, risk or physical-outcome language (PRD non-goals, master plan section 8). Explanations say why a route was recommended and never predict an outcome ("you will send"). No chance-of-success figure is shown in v1 (`predicted_send_probability` is null in all fixtures).
- Replay and any comparison speak about rankings, never about what a climber would have done (AGENTS.md replay rigor).
- No invented numbers: thresholds that the contract does not expose (for example how much history ends cold start) are not stated in copy.
- Board SVG is the visual centerpiece on Explore, Session Builder, Attempt Log and Decision detail; roles and states are encoded by shape, pattern and text, with color only as reinforcement.
- The demo board is labelled "SYNTHETIC DEMO BOARD" whenever `Board.synthetic` is true (master plan D1).

## 1. Map

```
                       +-----------------------+
   first visit ------> | J1 Onboarding         |  sign in / demo, board, angle,
   (no preferences)    | (onboarding.md)       |  level, goals, logging opt-in
                       +-----------+-----------+
                                   |
                                   v
 +------------------+     +------------------+     +----------------------+
 | J2 Explore       |<--->| J3 Session       |---->| J5 Decision detail   |
 | (explore.md)     |     | Builder          |     | "Why this route?"    |
 +--------+---------+     | (session-        |     | (decision-detail.md) |
          |               |  builder.md)     |     +----------+-----------+
          |               +--------+---------+                |
          |                        |                          v
          v                        v                +----------------------+
 +------------------+     +------------------+      | J6 Replay Lab (stub) |
 | J4 Attempt Log   |<----+ "Start" a route  |      | (replay-lab-stub.md) |
 | log / correct /  |     +------------------+      +----------------------+
 | retract / drafts |
 | (attempt-log.md) |      +-------------------------------+
 +------------------+      | J7 Observatory (stub)         |  demo/admin only
                           | (observatory-stub.md)         |
                           +-------------------------------+

   - - - - - - - - - - - -
   FUTURE (post-v1 epic E07, not designed):  [ Photograph a climb ]
   No screen, no fields, no endpoint, no promise. See section 9.
   - - - - - - - - - - - -
```

Primary navigation (mobile bottom bar, rail at >= 1024px): Explore, Session, Log, More. "More" holds Account and logging, Replay Lab (labelled preview), Engineering (demo builds only), Sign out.

| Journey | Entry | Screens | Endpoints used (all exist in openapi) |
|---|---|---|---|
| J1 | first visit, or `GET /v1/me` has `preferences: null` | Onboarding | `POST /v1/auth/session`, `GET /v1/me`, `GET /v1/boards`, `PUT /v1/me/preferences`, `POST /v1/me/consent`, `DELETE /v1/me/consent` |
| J2 | nav, end of J1, link from J4 | Explore | `GET /v1/routes`, `GET /v1/routes/{route_variant_id}`, `GET /v1/me`, `PUT /v1/me/preferences` |
| J3 | nav, "Build a session" prompts | Session Builder | `POST /v1/me/sessions/recommendations`, `GET /v1/routes/{route_variant_id}`, `GET /v1/me/attempts` |
| J4 | "Start" on a card, "Log an attempt" on Explore, nav | Attempt Log | `POST /v1/me/attempts`, `PATCH /v1/me/attempts/{attempt_id}`, `GET /v1/me/attempts`, `POST /v1/me/consent` |
| J5 | "Why this route" on a card | Decision detail | `GET /v1/me/decisions/{decision_id}`, `GET /v1/routes/{route_variant_id}` |
| J6 | More > Replay Lab, Decision detail | Replay Lab stub | `GET /v1/me/decisions/{decision_id}` (planned replay/compare endpoints are not called) |
| J7 | More > Engineering (demo builds) | Observatory stub | `GET /health/live`, `GET /health/ready` (planned admin endpoints are not called) |

Planned in the contract but with no screen in v1 (x-planned, 501): skill memory, export, account deletion, experiments, rollback. The UI neither calls nor hints at them beyond the J6/J7 placeholders.

## 2. Cross-cutting behaviour

1. Error envelope. Every error body is `{code, message, request_id, field_errors[], current_revision?}`. The UI writes its own copy keyed by status and `code` (fixture messages are generic: "Request cannot be applied") and keeps `request_id` in a "Details" disclosure. Codes seen in fixtures: `VALIDATION_ERROR`, `IDEMPOTENCY_CONFLICT`, `REVISION_CONFLICT`, `OCCURRED_AT_IN_FUTURE`. `field_errors` is empty in several 422 fixtures, so every form has a form-level fallback message.
2. Cross-user access returns 404, not 403 (ADR-0005). 404 copy therefore says "isn't available or isn't yours".
3. Session expiry. `POST /v1/auth/session` returns `expires_at`. Any 401 opens a sign-in dialog over the current screen; form values and local drafts are kept.
4. Offline. Reads show a labelled saved copy where a cache exists. Writes are only supported for new attempts (local draft with its own `Idempotency-Key`); everything else is disabled with a visible reason. Nothing is retried silently under a new key.
5. Freshness. Recommendation views always print `feature_as_of`; a later `received_at` on the newest attempt adds "your latest attempt may not be included yet".
6. Board change invalidation. Changing `board_id` or `angle_deg` in preferences clears any lineup that was built for the old values (PRD 4.3).
7. Synthetic labelling. See ground rules.

## 3. J1 First-run onboarding

Screen: `wireframes/onboarding.md`. Goal: a signed-in user with a compatible route search and an explicit, revocable logging choice.

- Entry: first visit with no token, or `GET /v1/me` returns `preferences: null` (resume at Step 1), or `consent: null` (logging Off; no forced prompt).
- Steps:
  1. Sign in with username and password, or "Try the demo account" (demo builds, seeded user from 06 section 12). Writes `username`, `password`; reads `token`, `expires_at`.
  2. Board. Reads `GET /v1/boards` `items[].board_id`, `provider`, `layout_id`, `size_id`, `synthetic`.
  3. Angle. Writes `angle_deg` (see needs: available angles).
  4. Level. Writes `initial_grade_band`. Helper text states what is used until history exists.
  5. Goals. Writes `goals[]` from TECHNIQUE, STRENGTH, ENDURANCE, PROJECT.
  6. Logging opt-in (default Off, not pre-checked). Writes `consent_version` only if On.
  7. Review and save: `PUT /v1/me/preferences` with `board_id`, `angle_deg`, `initial_grade_band`, `goals`, then `POST /v1/me/consent` if On.
- Data read: `token`, `expires_at`, `user_id`, `username`, `consent.version|consented_at|revoked_at`, `preferences.*`, `Board.*`.
- Data written: `preferences.*`, optionally a consent record.
- Exit: Explore filtered to the chosen board and angle (J2). A user who left logging Off sees a one-line banner "Logging is off. Turn it on any time in Account > Logging."
- Failure branches:
  - 401 at sign-in: "Username or password not recognised." (same text for any cause). Rate limiting (ADR-0005) has no 429 in the contract.
  - Token expires mid-flow: sign-in dialog; answers kept.
  - `listBoards` empty or failing: Retry, nothing to choose.
  - 422 on preferences: map `field_errors[].field`, else form-level message; answers kept.
  - Preferences saved but consent call fails: user continues with logging Off and an explicit banner; no silent retry.
  - Offline: can fill in, cannot save; save disabled with reason.
  - User declines consent: fully valid path. Explore works; Attempt Log shows the consent gate (J4); whether Session Builder works without consent is unconfirmed (needs contract change).
- Insufficient-data branch (expectation setting): at the Level step, "Until you log attempts, session picks are based on this level, your goals and each route's grade. If you opt in to logging, your attempts are also used so Beacon can avoid repeating routes too soon."

Later control: Account > Logging toggles consent. Turning off opens a confirm dialog, then `DELETE /v1/me/consent`. What happens to existing attempts is a retention question owned by the privacy document (BC-0007); the dialog copy is a placeholder until that exists.

## 4. J2 Explore routes

Screen: `wireframes/explore.md`.

- Entry: nav, end of J1, "Change route" from Attempt Log (picker mode returns `route_variant_id`).
- Steps: land with defaults from `preferences.board_id` and `preferences.angle_deg` -> optional filters (angle, `grade_min`, `grade_max`) -> list loads (`limit`, `cursor`) -> pick a row -> `GET /v1/routes/{route_variant_id}` draws the board -> inspect holds (pin, callout, list view) -> "Log an attempt" or back to list. "Use these as my defaults" calls `PUT /v1/me/preferences`.
- Data read: `items[].route_variant_id|board_id|angle_deg|name|grade_numeric|grade_label|grade_observation_count`, `next_cursor`; `placements[].hold_id|x|y|role`, `tags`, `missing_tags`.
- Data written: query parameters (URL state); optionally preferences.
- Exit: Attempt Log with `route_variant_id`; Session Builder via nav.
- Failure branches: empty result (copy suggests widening filters), 404 on a route (retired or not on this board), 422 on filters (client pre-check blocks grade from > grade to), 5xx per zone, 401 dialog, offline saved copy.
- Insufficient-data branch: a route with `grade_observation_count == 0` or `grade_numeric: null` is drawn with dotted outlines and the stamp "NOT ENOUGH DATA"; `missing_tags: true` prints "Style tags: not available for this route". Hidden gaps are never allowed.

## 5. J3 Build a session

Screen: `wireframes/session-builder.md`.

- Entry: nav "Session"; a prompt after logging an attempt.
- Steps: choose goal (TECHNIQUE, STRENGTH, ENDURANCE, PROJECT) and count (UI 3-6) -> "Build my session" sends `board_id`, `angle_deg` (from preferences), `goal`, `max_routes`, `session_id` (client UUID), `exclude_route_variant_ids` -> lineup renders in `position` order, route names and grades via `GET /v1/routes/{route_variant_id}` -> board shows the selected card's route with recommendation styling -> per card: Show on board, Why this route (J5), Start (J4), Not for me.
- Not for me: add to the exclusion list, re-POST with the same `session_id`; the response is a new `decision_id` and a replaced lineup with "NEW" stamps on changed items.
- Data read: `decision_id`, `policy_version`, `model_version`, `feature_as_of`, `cold_start`, `fallback_used`, `recommendations[].route_variant_id|position|reason_codes|uncertainty_label` (`rank_score` not shown here; `predicted_send_probability` never shown), route details; `GET /v1/me/attempts` for "logged this session" markers.
- Data written: the request; client-side exclusion list and recent decision ids.
- Exit: Attempt Log via Start; Decision detail via Why; Explore.
- Failure branches: 503 ("couldn't build", retry manually, previous lineup dimmed), 403 (account cannot build sessions, reason unspecified), 422 (settings invalid, check setup), 401 dialog, empty `recommendations` ("No routes fit this goal..."), board/angle changed (lineup cleared), offline (saved lineup with `feature_as_of`; Start still works in draft mode).
- Fallback branch (`fallback_used: true`): warning banner "SIMPLIFIED RECOMMENDATION: The full ranking step wasn't available, so this list uses a simpler fallback. No chance-of-success figures are shown." and the stamp on each card. Not an error; the list is still usable.
- Insufficient-data branch (`cold_start: true`, `uncertainty_label: "INSUFFICIENT_HISTORY"`): banner "Starting picks. Not enough logged attempts yet for history-based picks. What you get instead: routes ranked by how well their grade fits your goal and level, on your board and angle. Logging attempts adds your history." Each card shows "NOT ENOUGH HISTORY". No personal prediction is displayed, no threshold is promised.

## 6. J4 Log attempts (including correction and retraction)

Screen: `wireframes/attempt-log.md`.

- Entry: Start on a lineup card (arrives with `route_variant_id`, and a client-side "from lineup" flag for the RECOMMENDED stamp), "Log an attempt" in Explore or History, nav "Log".
- Steps (create): check `GET /v1/me` consent (gate if off) -> form with `route_variant_id`, `occurred_at`, `attempts_count`, `sent` (required, no default), optional `completion_fraction` (only when not sent), `perceived_effort`, `notes` -> `POST /v1/me/attempts` with `Idempotency-Key` generated at form open -> 201 (new) or 200 (idempotent replay, treated as saved) -> History row with revision 1 and inline status, no timer.
- Steps (correct): Edit on a row -> `PATCH /v1/me/attempts/{attempt_id}` with `If-Match: "<revision>"` and only changed fields -> `revision + 1`; row shows "CORRECTED". Route and time can't be patched (retract and re-log).
- Steps (retract / undo): Retract on a row -> confirm -> PATCH `{retract: true}` -> `status: RETRACTED`, revision + 1, row muted with stamp, hidden unless "Show retracted". History is kept; retracted attempts are not used for recommendations (06 section 8 uses ACTIVE only).
- Data read: `Attempt.attempt_id|route_variant_id|occurred_at|received_at|attempts_count|sent|completion_fraction|perceived_effort|revision|status`, `next_cursor`, route names via `getRoute`, `consent`.
- Data written: attempt fields above, `retract`, local drafts (body plus key).
- Exit: History; or Session Builder/Explore.
- Failure branches:
  - 403 on create: consent gate ("Logging is off, so this attempt wasn't saved") with Turn on / Not now.
  - 422 (`VALIDATION_ERROR`, `OCCURRED_AT_IN_FUTURE` on `occurred_at`): inline field messages or form-level fallback.
  - 409 `IDEMPOTENCY_CONFLICT`: same key, different body; dialog offering View saved attempt or Save as separate attempt (new key).
  - 409 `REVISION_CONFLICT` with `current_revision`: dialog with "Your change" beside "Latest saved"; re-apply with a new `If-Match` or discard.
  - 404 on patch: attempt unavailable.
  - 503 or timeout: "Nothing was recorded" (explicit failure) versus "We can't tell if it saved; trying again is safe" (unknown outcome); retry always reuses the key and body.
  - Offline: new attempts become drafts (DRAFT ON THIS DEVICE -> SAVING -> SAVED, or NEEDS ATTENTION); corrections and retractions need a connection.
- Insufficient-data branch: empty history states what logging does ("Beacon uses logged attempts to avoid repeating routes too soon. Personal send predictions aren't available.") and no completion is inferred: a "not sent" attempt with progress records progress only.

## 7. J5 View a decision's explanation

Screen: `wireframes/decision-detail.md`.

- Entry: "Why this route" (or "+N more") on a lineup card; link from Replay Lab.
- Steps: `GET /v1/me/decisions/{decision_id}` -> header (id, `cold_start`, `fallback_used`, `policy_version`, `model_version`, `feature_as_of`) -> selected route explanation (board with recommendation styling, grade, observations, reasons from `reason_codes`, ordering value from `rank_score` with a "relative order only" label, `uncertainty_label` stamp) -> choose another route in the list -> Log an attempt, Open in Replay Lab, Back to session.
- Data read: all `Decision` fields, `recommendations[].*`, route details via `getRoute`.
- Data written: none.
- Exit: Session Builder, Attempt Log, Replay Lab.
- Failure branches: 404 (decision missing or not yours), 401 dialog, 5xx per zone, listed route no longer available (row keeps its reason text), offline cached copy (decisions are immutable).
- Insufficient-data branch: `cold_start: true` shows "Starting picks. Beacon didn't have enough of your logged attempts for history-based picks, so this list is based on each route's grade, your angle and your goal. It doesn't estimate your chance of sending." The "Success estimate" line says it is not provided in this version.
- Limit stated on screen: feature values, candidates and request context are not in the public `Decision`, so the evidence shown is reason codes and the history time only.

## 8. J6 Replay Lab (stub) and J7 Observatory (stub)

J6, `wireframes/replay-lab-stub.md`
- Entry: More > Replay Lab (labelled preview), or Open in Replay Lab from J5.
- Steps: choose a recent decision (locally stored ids) or paste an id -> `GET /v1/me/decisions/{decision_id}` shows the original ranking read-only -> replay modes (Faithful, What-if, Incident; PRD 9.1) are shown disabled with "Replay isn't available yet".
- Data read: `Decision.*` as in J5. Data written: none. Planned replay and compare endpoints are never called.
- Exit: Decision detail or Session Builder.
- Failure branches: 404, invalid id format (client), 401, 5xx, offline cached copy; future `REPLAY_UNAVAILABLE_ERASED` copy reserved.
- Insufficient-data branch: a `cold_start` decision gets a note that a replay would use the same recorded, limited history.
- Wording guard: "ranking" only; the future replayed list carries the stamp "COUNTERFACTUAL RANKING"; no screen speaks of what the climber would have done.

J7, `wireframes/observatory-stub.md`
- Entry: More > Engineering (demo builds only; no role field exists to gate by).
- Steps: page load checks `GET /health/live` and `GET /health/ready`; "Check again" repeats. Everything else is listed as NOT CONNECTED with "No data source is connected for this in this version." and no numbers.
- Data read: `Health.status`, `X-Request-Id`, `ErrorEnvelope.code|message|request_id` on 503. Data written: none.
- Exit: More.
- Failure branches: ready 503 shows NOT READY; network failure shows DOWN with "No response"; offline keeps the last result labelled saved.
- Insufficient-data branch: all not-connected signals.

## 9. FUTURE entry point: "Photograph a climb" (post-v1 epic E07)

```
   [ Photograph a climb ]   <- FUTURE node, post-v1 epic E07 (master plan 7b)
```

This is a marker for the human's later goal, nothing more. It has no screen, no fields, no endpoint and no promise of what it will recognize or record. It is out of v1 and is PRD FR-14 territory that needs its own ADR and dataset plan. Rules for v1: no camera, upload or photo wording in any v1 screen, no permission prompts, no placeholder button, and no new contract fields. Design hooks that already exist at zero cost (free-text `provider`, user-defined boards, `geometry_meta_json`) are backend facts, not UI commitments. PRD section 10 states that video or imagery needs separately reviewed consent; that review would happen in E07 planning.

## 10. Copy bank (shared strings)

| Where | Copy |
|---|---|
| Cold start banner | "Starting picks. Not enough logged attempts yet for history-based picks. What you get instead: routes ranked by how well their grade fits your goal and level, on your board and angle. Logging attempts adds your history." |
| Fallback banner | "SIMPLIFIED RECOMMENDATION. The full ranking step wasn't available, so this list uses a simpler fallback. No chance-of-success figures are shown." |
| Freshness | "Picks use your history up to <time>." (+ "Your latest attempt may not be included yet." when applicable) |
| Offline | "You're offline. <what is shown>. <what needs a connection>." |
| Synthetic board | "SYNTHETIC DEMO BOARD. Practice routes made by a generator, not real climbs." |
| Unknown tags | "Style tags: not available for this route." |
| Grade basis | "Community grade" with observation count; never "easy" or "hard for you" |

## 11. Aggregated "needs contract change"

Items are grouped by when they block. IDs are cross-referenced by the per-screen lists. "Clarify" items need an answer, not necessarily a schema change.

### A. Needed before the real-API UI tickets (BC-0115 to BC-0118) can be built as designed

| # | Need | Screens | Interim in this design |
|---|---|---|---|
| A1 | Board geometry: `Board.holds[]` (hold_id, x, y), extent, orientation (y up or down) and units | Explore, Session, Attempt Log, Decision | draw only the selected route's placements; infer the frame; assume y up |
| A2 | `Attempt.notes` is accepted on create/patch but never returned | Attempt Log | field shown with "can't be shown here yet" helper |
| A3 | Route summary (`name`, `grade_label`, `grade_numeric`) embedded in `Recommendation` and `Attempt` | Session, Decision, Attempt Log, Replay | N+1 `getRoute` calls (max 6 per lineup), cached |
| A4 | Enumerations: reason codes (06 section 9 lists them; openapi types them as free strings), `uncertainty_label` values, `Preferences.goals` values, `initial_grade_band` values | Onboarding, Session, Decision | UI maps known values, prints unknown ones as text |
| A5 | Available angles per board (`Board.available_angles`) | Onboarding, Explore | free numeric angle validated by a `listRoutes` result |
| A6 | Grade table (label to `grade_numeric`, BC-0014) for `grade_min`/`grade_max` and `initial_grade_band` | Onboarding, Explore | numeric filter inputs |
| A7 | Consent: current `consent_version` and text; HTTP code for "consent missing" (06 says `CONSENT_REQUIRED`, openapi bare 403); revocation semantics for stored attempts | Onboarding, Attempt Log, Session | text and version bundled in the UI; 403 treated as consent-off |
| A8 | Error code list per status (401, 403, 404, 429 on `createSession`, 503) and populated `field_errors` for generic 422s | all | form-level fallback messages |
| A9 | Whether `createRecommendations` requires consent (403 reason) | Onboarding, Session | assume it does not; copy kept neutral |
| A10 | Single-attempt read, or latest `Attempt` in the `REVISION_CONFLICT` body | Attempt Log | re-read `listAttempts` pages |
| A11 | `listAttempts` ordering and whether `RETRACTED` items are returned | Attempt Log | assume newest first, retracted included |

### B. Needed for the decision explanation to meet US-05 and for post-slice screens

| # | Need | Screens |
|---|---|---|
| B1 | `Decision` evidence: per-recommendation values behind the reason codes (target grade, observation count, recency), `created_at`, request context (`goal`, `board_id`, `angle_deg`, `max_routes`, `session_id`) | Decision detail, Replay |
| B2 | Decisions list endpoint (collection read) | Replay Lab |
| B3 | Replay and compare response shapes, mode enum, counterfactual marker, `REPLAY_UNAVAILABLE_ERASED` status and envelope | Replay Lab |
| B4 | `Me.role` or equivalent to gate Replay compare and Observatory; feature flag for gated user features | Replay Lab, Observatory |
| B5 | Observatory signals: stream lag, watermark/checkpoint, feature freshness, inference latency, serving model version, experiment and rollback state, richer `Health` | Observatory |
| B6 | History sufficiency: `cold_start` is only a boolean; add history count and required minimum if a progress hint is wanted | Session, Decision |
| B7 | Dismissal/feedback recording and single-slot swap | Session |
| B8 | Revision history read (audit view) for attempts | Attempt Log |
| B9 | `placement_order` on `Placement`, `missing_tags` on `RouteVariantSummary`, tag and hold filters, result total, `min_obs` or an evidence enum | Explore |
| B10 | Attempt to recommendation link (`session_id`/`decision_id` on `Attempt`) | Attempt Log |
| B11 | Account creation (US-01), export and delete (US-08; `GET /v1/me/export` and `DELETE /v1/me` are 501 placeholders), optional history import and climbing frequency (PRD 3.1) | Onboarding, Account |
| B12 | "Desired duration" for a session (PRD 4.2 step 2) | Session |

### C. Disagreements between PRD, 06 and the live openapi (UI follows openapi)

| Topic | PRD / 06 | openapi 1.0.0 |
|---|---|---|
| Goal values | PRD 3.1: warm-up, technique, project, exploration; PRD 8.2 example: TECHNIQUE | `goal` enum TECHNIQUE, STRENGTH, ENDURANCE, PROJECT |
| Route count | PRD 3.1, 4.2: 3-6 routes | `max_routes` 1-20 (UI narrows to 3-6) |
| Angle query name | PRD 8.1: `angle` | `angle_deg` (06 section 5 agrees with openapi) |
| 403 consent code | 06 section 7: `CONSENT_REQUIRED` | 403 declared, no code |
| Attempt PATCH errors | 06: 409, 404 | adds 422 |
| Auth errors | 06: 401 | adds 422; ADR-0005 adds rate limit with no 429 |
| Recommendation failure | PRD 8.3: model timeout returns fallback with `fallback_used=true` | `createRecommendations` also declares 503; UI treats 200 + `fallback_used` as degraded success and 503 as failure |
| Model version example | PRD 8.2 example `grade-baseline-v1` | fixtures `"none"` (MVP per 06 section 10); UI prints values as given |
| Decision visibility | PRD 3.1 "snapshot of features, evidence" | `Decision` has reason codes only (see B1) |
