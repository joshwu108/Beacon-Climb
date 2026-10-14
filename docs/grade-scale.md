BLOCKED: difficulty_grades not extracted; sqlite not present at data/raw/kilter_splits.sqlite

# Grade scale mapping and ambiguity policy (BC-0014)

| Field | Value |
|---|---|
| Ticket | BC-0014; depends on BC-0002 (Gate D0, blocked) |
| Contract file | `contracts/catalog/grade-scale.v1.json`, version `1.0.0`, status `BLOCKED_PENDING_DIFFICULTY_GRADES_EXTRACTION`, `entries: []` |
| Reference code | `ml/datasets/grade_scale.py` (stdlib; the policy only, no grade data) |
| Tests | `ml/tests/test_grade_scale.py` |
| Date | 2026-10-14 |

## Blockers

1. The sqlite is absent, so `difficulty_grades` can't be read and the conversion table is
   **not populated.** See `docs/data-source-audit.md` (Blockers).
2. Rights: the table's contents come from the same source as the dataset, so the same
   UNKNOWN-NEEDS-HUMAN status applies. A filled table is a small derived look-up. Whether
   committing it counts as acceptable reuse is part of the human's section 7 sign-off in
   the audit. Until signed, entries are filled locally only.
3. Filling `entries` changes a versioned contract. It needs Codex integrator approval
   (AGENTS.md "Contracts") before any consumer relies on it.

This document does **not** state the Kilter difficulty → V/Font mapping from memory. The
only publisher statement is the dataset card's filter text "`difficulty_numeric ≤ 30.5`
(~ V13)". That too is to be verified against `difficulty_grades`.

## Field semantics (catalog v1)

| Field | Meaning |
|---|---|
| `grade_numeric` | The raw provider float (`difficulty_numeric`, a fractional community average). Stored and used as the ML label unrounded. |
| `grade_label` | A display string chosen by the ambiguity policy below, or `"ungraded"`. |
| `grade_band` | A UI band over `grade_label`. Boundaries are **not defined** in 1.0.0 because they depend on the entries. |

Grades are subjective community labels with rater variance. They are not ground-truth
physical difficulty (PRD §7.1). Model A reports MAE on the raw `grade_numeric`;
"within one V-grade" is a secondary metric computed through this table (PRD §7.2;
`planning/09-prd-critique.md` D5).

## Method (NOT YET EXECUTED; run once the sqlite is present)

The commands were syntax-checked against a synthetic scratch fixture outside the repo.
They have not been run on Kilter data, so no Kilter numbers exist yet.

```sh
DB=data/raw/kilter_splits.sqlite
# 1. look-up table schema, then contents (expected to be a small reference table; columns to be verified)
sqlite3 -readonly "$DB" ".schema difficulty_grades"
sqlite3 -readonly -header -column "$DB" "SELECT difficulty, boulder_name FROM difficulty_grades ORDER BY difficulty;"

# 2. histogram of difficulty_numeric per split, floor buckets (CAST truncates = floor for non-negative values)
for t in kilter_train kilter_val kilter_test; do
  sqlite3 -readonly "$DB" "SELECT '$t', CAST(difficulty_numeric AS INTEGER) AS floor_bucket, COUNT(*) FROM $t GROUP BY floor_bucket ORDER BY floor_bucket;"
done

# 3. range, nulls and how many labels are fractional (drives how much the ambiguity policy matters)
sqlite3 -readonly "$DB" "SELECT 'min', MIN(difficulty_numeric), 'max', MAX(difficulty_numeric), 'nulls', SUM(difficulty_numeric IS NULL), 'non_integer', SUM(difficulty_numeric <> CAST(difficulty_numeric AS INTEGER)) FROM (SELECT difficulty_numeric FROM kilter_train UNION ALL SELECT difficulty_numeric FROM kilter_val UNION ALL SELECT difficulty_numeric FROM kilter_test);"
```

| Quantity | Value |
|---|---|
| `difficulty_grades` rows / columns | not measured |
| Observed min / max `difficulty_numeric` | not measured |
| Null `difficulty_numeric` | not measured |
| Fraction of non-integer `difficulty_numeric` | not measured |
| Per-split histogram | not measured |
| How `boulder_name` encodes Font and V (one string or two; separator) | to be verified against `difficulty_grades` |

Filling the table (after unblock): parse each `difficulty_grades` row into
`{difficulty_numeric:int, v_grade:str, font_grade:str}`, with both labels taken from the
**same row**. Record the parse rule and the manifest file name in `source`, set
`source.verified: true`, change `status`, and paste the outputs of commands 1 to 3 into
the table above. Never type an entry by hand.

## Ambiguity policy (normative; mirrored in the JSON `ambiguity_policy`)

Entries are sorted by strictly increasing integer `difficulty_numeric`. For a raw value
`x`:

1. `x` is null, NaN or not a number → `"ungraded"`.
2. `x < min_entry − 0.5` or `x > max_entry + 0.5` → `"ungraded"`. The table is never
   extrapolated.
3. Otherwise, select the entry with the smallest `|x − difficulty_numeric|`. **Exact ties
   go to the LOWER entry.**
4. The selected entry supplies **both** `v_grade` and `font_grade`, and the UI shows both.
   Neither label is converted from the other.
5. `grade_numeric` keeps the raw `x`. Rounding applies to display labels only.

Why nearest and not floor: a floor biases every fractional community average down by up
to one step. Nearest has at most half a step of error. The lower tie-break is the
conservative choice, so a route is never shown harder than the evidence supports.

Monotonicity: with sorted entries and a fixed tie-break, the selected entry index is
non-decreasing in `x`. V-scale labels are likely coarser than the numeric scale, so
several entries may share one `v_grade`. The V-number sequence must therefore be
non-decreasing, not strictly increasing. Both properties are tested.

## Contract and integration notes

- **Needs contract change (pending approval):** populating `entries`, setting
  `status`/`source.verified`, and defining `grade_band` boundaries are changes to
  `contracts/catalog/grade-scale.v1.json`. Propose them as version `1.1.0` and get Codex
  integrator approval first.
- **Possible OpenAPI follow-up (not made; outside this ticket's paths):** the OpenAPI
  `Route` schema exposes `grade_numeric` and `grade_label` but no `grade_band`.
  `Preferences.initial_grade_band` is a free string (examples use `"V2-V4"`). Whether the
  UI band should be a Route field is for the integrator to decide.
- **Fixture check for the integrator:** `contracts/examples/getRoute/response.json` and
  `listRoutes/response.json` pair `grade_numeric: 17` with `grade_label: "V2"`. That pairing
  is unverified against `difficulty_grades`. If the fixtures are meant to be synthetic,
  label them so. Otherwise, re-check them once entries exist.

## Tests

`uv run --directory ml --no-project --with pytest==9.1.1 pytest tests/test_grade_scale.py -q -rs`
checks the contract structure, `entry_schema` against the reference helper, and that a
BLOCKED status means empty and unverified entries. It also runs the policy on
**synthetic** entries (labels `SYN-V*`/`SYN-F*`): exact round trip, nearest with lower
tie-break, ungraded cases, rejection of malformed entries, and monotonicity on a 0.01 grid.
`test_real_entries_populated_and_round_trip` is **skipped**, with the reason "entries
empty: difficulty_grades not extracted (sqlite absent, BC-0002 blocked)", until real
entries exist. Observed results are in the BC-0014 handoff.
