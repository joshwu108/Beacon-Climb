# Wireframe: Attempt Log (history, log an attempt, correct, retract, offline drafts)

Contract basis: `POST /v1/me/attempts` (`createAttempt`, `Idempotency-Key`), `PATCH /v1/me/attempts/{attempt_id}` (`patchAttempt`, `If-Match`), `GET /v1/me/attempts` (`listAttempts`), `POST` and `DELETE /v1/me/consent`, `GET /v1/routes/{route_variant_id}`; schemas `AttemptCreate`, `AttemptPatch`, `Attempt`, `ErrorEnvelope` in `contracts/openapi.yaml` 1.0.0; fixtures under `contracts/examples/createAttempt`, `patchAttempt`, `listAttempts`; PRD 3.1 Attempt Log, 4.3, US-03, 8.3; 06 section 7. Journey: `../journeys.md` J4.

## 1. Purpose

Record what happened on a route in a few taps, without ever claiming something that was not observed: "sent" is an explicit choice with no default, partial progress is recorded as progress, and a correction or retraction creates a new revision instead of rewriting history. Works offline through clearly labelled local drafts. Logging is opt-in; when it is off the screen says so before the user types anything.

## 2. Wireframe, mobile (320-480px)

Routes: `/log` (history), `/log/new?route=<route_variant_id>` (form), `/log/<attempt_id>/edit` (form, prefilled).

```
A. History  /log
+------------------------------------------+
| BEACON CLIMB   [Synthetic demo . 35 deg] |
+------------------------------------------+
| Attempt log                         (h1) |
| [ Log an attempt ]                       |
+------------------------------------------+
| WAITING TO SAVE (2)                 (h2) |  local drafts, only if any
| [DRAFT ON THIS DEVICE] Crimp Line        |
|   3 attempts . Not sent   [Edit][Save now]|
| [SAVING...]  Traverse 1                  |
+------------------------------------------+
| History                             (h2) |
| Synthetic Traverse 1   V2                |
|   Oct 14, 14:00 . 2 attempts . Sent      |
|   Effort 6/10 . Revision 1               |
|   [Edit] [Retract]                       |
| Synthetic Crimp Line   V3                |
|   Oct 14, 13:20 . 4 attempts . Not sent  |
|   Progress 60% . [CORRECTED rev 2]       |
| [RETRACTED] Synthetic Arete  V2 (muted)  |
| [ ] Show retracted                       |
| [ Load more ]                            |  next_cursor
+------------------------------------------+

B. Form  /log/new?route=...
+------------------------------------------+
| < Back                Log an attempt (h1)|
+------------------------------------------+
| Logging is off.  (only if consent absent)|
| [ Turn on logging ] [ Not now ]          |
+------------------------------------------+
| +--------------------------------------+ |
| | route board (compact)  [RECOMMENDED] | |  only when arrived via a lineup card
| |   (F)<>  (H) [F] ((S))               | |
| +--------------------------------------+ |
| Synthetic Traverse 1   V2  [Change route]|
| When      [2026-10-14 14:00 +00:00]      |
| Attempts  [ - ]  2  [ + ]                |
| Did you send it?          (required)     |
|   ( ) Sent    ( ) Not sent               |
| How far did you get? (optional)          |  only when "Not sent"
|   [---o------] 60%                       |
| Perceived effort (optional)              |
|   1 2 3 4 5 6 7 8 9 10                   |
| Notes (optional)             0/500       |
|   [                                  ]   |
| [ Save attempt ]   [ Cancel ]            |
+------------------------------------------+
```

After a successful save the user returns to History with an inline, non-timed status on the new row: "Saved as revision 1." The row's Edit and Retract buttons are the undo path; there is no auto-dismissing toast.

Retract opens a confirm dialog: "Retract this attempt? It stays in your history marked Retracted. Retracted attempts are not used for your picks." [Retract] [Keep attempt].

### >= 1024px

History is the main column. The form opens as the `aside` ("Log an attempt") on the right with the full-height board above the fields; History stays visible and the saved row appears live. Nav rail on the left. Conflict dialog is centered.

## 3. Fields and contract sources

### Create (`POST /v1/me/attempts`)

| UI element | Contract field | Rules |
|---|---|---|
| (header) | `Idempotency-Key` header, string 1-128 chars | UUID generated when the form opens or a draft is created; stored with the draft; reused for every retry of the same submission; never reused for a different submission |
| Route | `route_variant_id` | from the link/selection; read-only in the form, changed via "Change route" |
| When | `occurred_at` (date-time) | default now; sent with a UTC offset; client blocks more than five minutes in the future (server enforces: 422 `OCCURRED_AT_IN_FUTURE`) |
| Attempts | `attempts_count` (integer >= 1) | default 1 |
| Did you send it? | `sent` (boolean, required) | two radios, nothing preselected; Save stays inactive with a reason until chosen |
| How far did you get? | `completion_fraction` (0 to 1, optional) | shown only when Not sent; slider/number in percent, converted to a fraction; omitted when untouched; never inferred from other answers |
| Perceived effort | `perceived_effort` (integer 1-10, optional) | descriptive scale labels "1 very light" to "10 maximum effort" |
| Notes | `notes` (string, max 500) | live counter; plain text only |

### Response and list (`Attempt`)

| UI element | Contract field | Notes |
|---|---|---|
| Row id and keys | `attempt_id`, `route_variant_id` | |
| Route name and grade | `GET /v1/routes/{route_variant_id}` `name`, `grade_label` | `Attempt` has no route name; resolved per distinct route, cached |
| Date | `occurred_at` | shows local time; screen reader gets full date and zone |
| Saved time (details) | `received_at` | "Saved 14:00" |
| Attempt count | `attempts_count` | |
| Sent / Not sent | `sent` | text, never only an icon or color |
| Progress | `completion_fraction` (nullable) | printed only when not null |
| Effort | `perceived_effort` (nullable) | printed only when not null |
| Revision | `revision` (integer) | "Revision N"; N > 1 shows the stamp "CORRECTED" |
| Retracted | `status` (`ACTIVE` or `RETRACTED`) | stamp "RETRACTED"; row muted and out of the default list (toggle "Show retracted") |
| Paging | `next_cursor`, query `cursor`, `limit` (1-100) | "Load more" |
| Notes | not returned by `Attempt` | see needs contract change |

### Correct and retract (`PATCH /v1/me/attempts/{attempt_id}`)

| UI element | Contract field | Rules |
|---|---|---|
| (header) | `If-Match: "<revision>"` | quoted, from the row's `revision`, pattern `"N"` |
| Edit form | `attempts_count`, `sent`, `completion_fraction`, `perceived_effort`, `notes` | only changed fields are sent |
| Retract | `retract: true` | confirm dialog first; response `status: RETRACTED`, `revision + 1` |
| Conflict | 409 `REVISION_CONFLICT` with `current_revision` | see matrix |

`route_variant_id` and `occurred_at` are not in `AttemptPatch`: to change them the user retracts and logs a new attempt; the Edit form says so ("To change the route or time, retract this attempt and log a new one.").

### Consent gate

| UI element | Contract | Notes |
|---|---|---|
| Logging on/off | `GET /v1/me` `consent` (null or `revoked_at` set means off) | gate shown before the form fields |
| Turn on logging | `POST /v1/me/consent` `consent_version` | same explicit text as onboarding Step 5 |
| Server refusal | 403 on `createAttempt` | treated as "logging is off" (06 section 7 calls the code `CONSENT_REQUIRED`; openapi shows only 403) |

### Offline drafts (client only, no new endpoint)

A draft stores the exact request body plus its `Idempotency-Key` in local storage. Sync states and their text:

| State | Stamp text | Meaning |
|---|---|---|
| Waiting | DRAFT ON THIS DEVICE | not sent yet |
| Syncing | SAVING | request in flight |
| Saved | SAVED | 201 or 200 (idempotent replay) received; draft removed, row appears in History |
| Needs attention | NEEDS ATTENTION | permanent failure (422, 403, 409); user must edit, turn on logging, or discard |

Retry of a waiting or interrupted draft always reuses its key and body, so a retry can't create a second attempt. Editing a draft after an unknown-outcome send first refreshes History: if a matching server attempt exists the user chooses "Update the saved attempt" or "Save as a separate attempt" (new key); this avoids silent duplicates. Drafts are cleared on sign-out after a confirm. Corrections and retractions of saved attempts need a connection (disabled with reason).

## 4. Board and hold states on this screen

The compact board shows the selected route for reference. Holds are inspectable (pin and callout) but do not record progress: the contract has no hold order, so progress is entered with the slider, not by tapping holds.

| State | Shape | Pattern | Text channel | Color (redundant) |
|---|---|---|---|---|
| START / HAND / FOOT / FINISH | ringed circle / circle / hatched small square / diamond with bar | solid / solid / hatch / solid | START and FINISH labels always; others on focus/pin | `--color-hold-*` |
| Selected (pinned) | 3px outer ring + gap + check badge | ring | callout "Selected"; `aria-pressed` | ink |
| Recommended | four accent rays per hold | solid rays | stamp "RECOMMENDED"; shown only when the user arrived from a session lineup (client navigation state) | `--color-accent` |
| Insufficient data | dotted outlines, hatch fill, "?" badge | dotted | stamp "NOT ENOUGH DATA", caption "No logged grade observations for this route yet." | neutral |

## 5. State matrix

Rows cover History (list) and Form unless noted.

| State | What is shown | What is actionable | Copy |
|---|---|---|---|
| Loading | History: 5 skeleton rows at final height; Form: route sheet skeleton, fields visible. Saving: Save button "Saving" with `aria-busy`, fields stay visible | Nothing double-submittable while saving | Live: "Loading your attempts"; "Saving attempt" |
| Empty | History with no attempts | "Log an attempt", "Explore routes" | "No attempts logged yet. When you log attempts they appear here, and Beacon uses them to avoid repeating routes too soon. Personal send predictions aren't available." |
| Error 4xx | 401: sign-in dialog; drafts kept. 403 on create: consent gate with explanation. 404 on patch: notice, row removed after reload | Sign in again; Turn on logging; Reload | 401: "Your session ended. Sign in again. Your drafts are still on this device." 403: "Logging is off, so this attempt wasn't saved." 404: "This attempt isn't available any more." |
| Error 5xx / network | Form: inline panel above Save, values kept, key kept. Result is either "nothing recorded" (explicit 503) or "unknown" (timeout/offline mid-request) | "Try again" (same key); "Keep as draft" | 503: "We couldn't save this attempt. Nothing was recorded. Try again." Unknown: "We didn't get a reply, so we can't tell if it saved. Trying again is safe and won't create a duplicate." |
| Validation 422 | Error summary with links; fields marked; `field_errors[].field` mapped (`occurred_at`, `attempts_count`, `completion_fraction`, `perceived_effort`, `notes`); `OCCURRED_AT_IN_FUTURE` shown on When; empty `field_errors` -> form-level message | Fix and resave | `OCCURRED_AT_IN_FUTURE`: "That time is in the future. Pick a time that has already happened." Fallback: "We couldn't save this attempt. Check the highlighted answers." Client: "Choose Sent or Not sent." |
| Conflict 409 | `IDEMPOTENCY_CONFLICT` (create): dialog. `REVISION_CONFLICT` (patch, with `current_revision`): dialog showing "Your change" beside "Latest saved" (reloaded from `listAttempts`) | Create: "View saved attempt" / "Save as separate attempt" (new key). Patch: "Re-apply my change to the latest" (new `If-Match`) / "Discard my change" | Idempotency: "This submission was already saved with different details. Choose what to do." Revision: "This attempt was changed elsewhere (now revision 3). Review the latest, then re-apply your change." |
| Insufficient data | Not a state of the log itself. Per-route: board dotted + "NOT ENOUGH DATA" when the route has no grade observations; the empty-history copy states what logging does and that predictions aren't available | Logging works normally | see Empty and board caption |
| Offline | Warning banner; Create saves a local draft with stamp DRAFT ON THIS DEVICE; Edit/Retract of saved rows disabled with reason; Waiting-to-save section shows count; auto-sync on reconnect with the same keys, announced politely | Save draft, edit/discard draft, "Save now" | "You're offline. This attempt will be kept on this device and saved when you're back online. Editing saved attempts needs a connection." |

## 6. Accessibility

### Landmarks and headings

| Landmark | Content |
|---|---|
| `header` | brand, board/angle chip, synthetic stamp |
| `nav aria-label="Primary"` | Explore, Session, Log (`aria-current="page"`), More |
| `main` | page content |
| `section aria-labelledby` "Waiting to save" | draft rows (only when present) |
| `section aria-labelledby` "History" | `ul` of attempts |
| `form aria-label="Log an attempt"` | the form; on >= 1024px inside `aside aria-label="Log an attempt"` |
| `section aria-label="Route board"` (form) | board toolbar and SVG |
| `div role="alertdialog"` | retract and conflict dialogs (modal, focus trapped) |
| `footer aria-label="Data and privacy"` | data note |

Heading outline: History: `h1` Attempt log, `h2` Waiting to save, `h2` History, `h3` per row = route name. Form: `h1` Log an attempt (or "Edit attempt"), `h2` "Route", `h2` "Details"; error summary `h2` "There is a problem".

### Keyboard path

History: skip to main -> nav -> Log an attempt -> each Waiting row (Edit, Save now, Discard) -> Show retracted checkbox -> each History row (Edit, Retract) -> Load more.

Form: skip to main -> nav -> consent gate buttons (only when shown) -> Change route -> Board|List toggle -> zoom controls -> Labels -> Skip board -> **board (roving)** -> When -> Attempts minus / input / plus -> Sent radios (one stop, arrows) -> Progress (only when visible) -> Effort (radiogroup 1-10, one stop, arrows; Home/End = 1/10) -> Notes -> Save attempt -> Cancel.

- Board keys: arrows move to the nearest hold in that direction; Enter or Space toggles the pinned selection; Esc closes the callout then exits to the board frame; Home/End first/last hold; +/-/0 zoom.
- Enter in the Notes textarea inserts a newline; Enter elsewhere submits. Esc in the form does not discard: on >= 1024 it closes the aside after an unsaved-changes confirm.
- On validation failure focus moves to the error summary; each link focuses its field. On success, focus moves to the new History row's heading and the live region says "Attempt saved as revision 1".
- Dialogs: focus moves to the dialog heading, Tab is trapped, Esc cancels (Keep / Discard), focus returns to the invoking button.
- Sync-state changes of drafts are announced via `aria-live="polite"`; errors via `role="alert"`.

### Focus and motion

- Two-tone `:focus-visible` ring everywhere; checked radios show a filled inner mark plus the ring, not color alone.
- `sheetOpenTl` (form aside / dialogs) becomes an instant opacity change under `prefers-reduced-motion: reduce`; stamps never animate.
- No timed controls: nothing auto-dismisses; no session timer on the form.
- Inputs 16px minimum font size, 44px minimum target; the effort scale buttons are 44px each (wraps to two rows at 320px).

## 7. Needs contract change (this screen)

1. `Attempt` does not return `notes` (it is in `AttemptCreate` and `AttemptPatch`), so saved notes can't be displayed or edited with their current text. The form shows "Notes are saved but can't be shown here yet" until fixed. Needs `notes` (nullable) on `Attempt`.
2. The contract has no single-attempt read: the conflict flow re-reads pages of `listAttempts` to find the latest revision. Needs a single-attempt read (or the 409 body to carry the latest `Attempt`).
3. No revision history read: the UI can show only the current `revision`. If an audit view is wanted (US-03 "versioned events/auditable state"), needs a revision-history read for one attempt (a new endpoint, to be proposed).
4. Whether `listAttempts` returns `RETRACTED` items (the `status` enum implies it) and its sort order (by `occurred_at`?).
5. Whether a retracted attempt can be restored (no `restore` flag in `AttemptPatch`); UI offers no restore.
6. Error codes: only `VALIDATION_ERROR`, `IDEMPOTENCY_CONFLICT`, `REVISION_CONFLICT`, `OCCURRED_AT_IN_FUTURE` appear in fixtures; `CONSENT_REQUIRED` appears in 06 only. Need the code list for 401/403/404/503 and `field_errors` populated for generic 422s (fixtures show empty arrays).
7. Attempts carry no `session_id`/`decision_id` link to the recommendation that led to them, so the UI can't show "from lineup" after the fact; the RECOMMENDED stamp is navigation state only.
8. `Attempt` has no route name/grade, so each row needs `getRoute`; proposed embedded route summary.
9. PATCH cannot change `occurred_at` or `route_variant_id` (by design?); confirm.
10. Time-zone: confirm `occurred_at` accepts offsets and is returned normalized (fixtures use `Z`).
