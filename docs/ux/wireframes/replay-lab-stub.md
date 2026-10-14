# Wireframe: Replay Lab (stub)

Contract basis: `GET /v1/me/decisions/{decision_id}` (live), `GET /v1/me/decisions/{decision_id}/replay` and `POST /v1/me/decisions/{decision_id}/compare` (both `x-planned: true`, 501 in v1) in `contracts/openapi.yaml` 1.0.0; PRD 3.1 Replay Lab, 9.1; US-06, FR-10; master plan D2/D3 (replay read path is scheduled after the vertical slice). Journey: `../journeys.md` J6.

## 1. Purpose

A deliberate placeholder for the signature feature. It explains what replay will be, lets a person open one past decision and see its recorded original ranking (real data from `getDecision`), and shows the replay and compare controls disabled with a "not available yet" label. It makes no request to the planned replay or compare endpoints and invents no results.

The wording rule that carries over to the real feature: a replay compares **rankings** computed from the same recorded pre-decision information. It does not show what the climber would have done, and no screen may present a replayed rank as an observed outcome (AGENTS.md replay rigor; PRD 3.1).

This screen has no board SVG. A later version may show two ranked lists side by side; when it does, each replayed list carries a text stamp "COUNTERFACTUAL RANKING" (shape and text channel; color is never the only signal).

## 2. Wireframe, mobile (320-480px)

Route `/replay?decision_id=<uuid>` (reached from "More", or from "Open in Replay Lab" on Decision detail).

```
+------------------------------------------+
| BEACON CLIMB   [Synthetic demo . 35 deg] |
+------------------------------------------+
| Replay Lab                          (h1) |
| [PREVIEW - NOT AVAILABLE YET]            |
| Replay re-ranks a past recommendation    |
| using only the information recorded at   |
| the time. It compares rankings. It does  |
| not show what you would have done or     |
| whether you would have sent.             |
+------------------------------------------+
| Choose a past decision              (h2) |
| Recent on this device:                   |
|  ( ) Oct 14 14:02  4 routes              |
|  ( ) Oct 14 13:10  3 routes              |
| or enter a decision id                   |
| [____________________________] [ Open ]  |
+------------------------------------------+
| Original ranking (as recorded)      (h2) |
| Policy heuristic-v1 . No learned model   |
| History up to Oct 14 13:59               |
| 1 Synthetic Traverse 1       V2          |
|   Matches your board and angle . Grade   |
|   fits your goal   Ordering value 0.72   |
| 2 Synthetic Crimp Line       V3          |
+------------------------------------------+
| Replay modes                        (h2) |
| ( ) Faithful: re-rank from the stored    |
|     information. Disabled                |
| ( ) What-if: change the policy, keep the |
|     same recorded information. Disabled  |
| ( ) Incident: look for what changed.     |
|     Disabled                             |
| [ Run replay ] disabled                  |
| Not available yet.                       |
+------------------------------------------+
| Replay result                       (h2) |
| +- - - - - - - - - - - - - - - - - - -+  |
| : Nothing to show yet.                :  |  dotted placeholder
| +- - - - - - - - - - - - - - - - - - -+  |
+------------------------------------------+
```

### >= 1024px

Two columns inside the content width: left = chooser and modes; right = two list columns labelled "Original (recorded)" and "Replay (counterfactual ranking)", the second dotted and empty. Nav rail on the left.

## 3. Fields and contract sources

| UI element | Contract source | Direction | Notes |
|---|---|---|---|
| Recent decisions list | client-stored `decision_id` values (written when a lineup is received) with a client-recorded receive time | read (local) | local data only; stores ids and time, nothing else; cleared on sign-out. There is no list endpoint |
| Decision id input | path param `decision_id` (uuid) | write (path) | validated as a UUID before any request |
| Original ranking | `GET /v1/me/decisions/{decision_id}` `recommendations[].position`, `route_variant_id`, `reason_codes[]`, `rank_score` | read | real data, shown read-only |
| Route name and grade | `GET /v1/routes/{route_variant_id}` `name`, `grade_label` | read | |
| Policy, model, history time | `Decision.policy_version`, `model_version`, `feature_as_of` | read | same display rules as Decision detail |
| Starting-picks note | `Decision.cold_start` | read | |
| Replay modes, Run replay | `GET .../replay`, `POST .../compare` | none | **no request is made in v1**; the controls are disabled. Modes (Faithful, What-if, Incident) come from PRD 9.1 |
| Reason text | table in `session-builder.md` | read | not redefined |

## 4. Board and hold states

Not applicable: no board SVG on this screen. Hold-state grammar is unchanged elsewhere (see `explore.md`, `session-builder.md`, `attempt-log.md`, `decision-detail.md`). The dotted placeholder uses the insufficient-data convention (dotted outline + text) so the "nothing yet" state is carried by text, not color.

## 5. State matrix

| State | What is shown | What is actionable | Copy |
|---|---|---|---|
| Loading | Skeleton table (3 rows) in Original ranking; chooser stays | Chooser, nav | Live: "Loading the recorded decision" |
| Empty | No recent decisions on this device and no id entered: chooser shows the empty message; sections below hidden | Enter an id; "Build a session" | "No past decisions on this device yet. Build a session, then come back, or paste a decision id." |
| Error 4xx | 404 on `getDecision` (also for someone else's id by design): notice under the chooser. 401: sign-in dialog. Invalid id format is caught before any request (client) | Edit id; Sign in again | 404: "We couldn't find that decision. It may be old, or it isn't yours." Format: "That doesn't look like a decision id." 401: "Your session ended. Sign in again." |
| Error 5xx / network | Panel in the Original ranking section, request id in "Details" | "Try again" | "The recorded decision couldn't be loaded. Try again." |
| Validation 422 | Not defined for `getDecision`; only the client-side id check applies | n/a | n/a |
| Conflict 409 | Not applicable | n/a | n/a |
| Insufficient data | Decision with `cold_start: true`: note above the table. Future erased decision (`REPLAY_UNAVAILABLE_ERASED`, PRD 9.1; not in contract): the replay area says why it can't run | Original ranking still viewable if the decision loads | Cold start: "This was a starting pick, based on grade, angle and goal because there wasn't enough history. A replay would use the same recorded information." Erased: "Replay isn't available for this decision because the underlying data was deleted." |
| Offline | Cached decisions (opened before) show with "SAVED COPY"; new ids can't be opened; replay area unchanged (already disabled) | Read cached decision | "You're offline. Showing a saved copy. Opening other decisions needs a connection." |
| Planned-feature (stub-specific) | Replay modes and Run replay disabled, status stamp "PREVIEW - NOT AVAILABLE YET" | none; disabled controls have visible reason text and `aria-disabled` | "Replay isn't available yet." If a planned endpoint ever returns 501 `ErrorEnvelope`: same message, no retry button |

## 6. Accessibility

### Landmarks and headings

| Landmark | Content |
|---|---|
| `header` | brand, board/angle chip, synthetic stamp |
| `nav aria-label="Primary"` | links; "More" lists Replay Lab with the text "preview" |
| `main` | all sections |
| `section aria-labelledby` x4 | chooser, original ranking, replay modes, replay result |
| `aside aria-label="About replay"` (>= 1024) | short definition and the counterfactual wording rule |
| `footer aria-label="Data and privacy"` | data note |

Heading outline: `h1` Replay Lab; `h2` Choose a past decision; `h2` Original ranking (as recorded); `h2` Replay modes; `h2` Replay result. The preview stamp is text in a `p`, not a heading.

### Keyboard path

Tab order: skip to main -> nav -> recent-decision radios (one stop, arrows) -> id input -> Open -> (after load) ranking rows are static text, no stops -> replay mode radios (focusable but `aria-disabled="true"`; arrows still move so users can read each description) -> Run replay (focusable, `aria-disabled`, with `aria-describedby` pointing to "Not available yet") -> nothing else.

- Disabled controls remain focusable on purpose, so keyboard and screen-reader users can discover why they don't work; activation does nothing and announces "Replay isn't available yet".
- Enter in the id input = Open. Esc clears the id input only when it is focused and non-empty.
- After Open, focus moves to the `h2` "Original ranking" and the live region announces "Loaded decision with 4 routes".
- No board; no roving-tabindex widgets on this screen.

### Focus and motion

- Two-tone `:focus-visible` ring on every control including disabled-but-focusable ones.
- No animation beyond a 150 ms opacity fade of the loaded table; removed under `prefers-reduced-motion: reduce`.
- Dotted placeholder is `aria-hidden`; the adjacent text carries the meaning.

## 7. Needs contract change (this screen)

1. List endpoint for past decisions (a collection read on the decisions resource, to be proposed) with `decision_id`, `created_at`, request context; the stub relies on locally stored ids.
2. Response shapes for `GET .../replay` and `POST .../compare` (currently only 501 envelopes), including the replay mode enum (Faithful, What-if, Incident), a per-list marker that distinguishes a counterfactual ranking from an original one, and the pinned `policy_version`/`model_version`/feature digest used for the replay.
3. Error code `REPLAY_UNAVAILABLE_ERASED` and its HTTP status, plus its envelope shape.
4. Who may use compare (PRD 8.1 "admin/dev or gated user feature"): there is no role or feature flag in `Me`, so the UI can't decide whether to show the screen. Interim: shown as a labelled preview to every signed-in user, with all actions disabled.
5. `rank_score` meaning and range, so original and replayed lists can be compared without implying a probability.
