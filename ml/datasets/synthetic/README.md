# ml/datasets/synthetic — SYNTHETIC demo board (BC-0104)

**SYNTHETIC.** Every byte under this directory is invented by `generate.py` from a seeded random
number generator. There is no real board, no real route, no real setter and no real grade here.
`grade_numeric` is a toy function of geometry plus noise; it is **NOT a difficulty model** and
must never be used as evidence of anything (not of route difficulty, not of model accuracy, not of
anyone's climbing).

## What it is for

The committed fixture `fixtures/demo-board-v1.jsonl` is the catalog-v1 input for the vertical
slice: importer (BC-0105), API fixtures, UI demos and replay tests. It validates against
`contracts/catalog/catalog-v1.schema.json` plus the cross-line rules in
`contracts/catalog/README.md` (checked in CI by `test/contracts/catalog.test.cjs`).

| Property | Value |
|---|---|
| File | `fixtures/demo-board-v1.jsonl`, 404,201 bytes, 302 lines (manifest, 1 board, 300 routes) |
| Manifest | `source=synthetic-demo`, `version=demo-board-v1`, `synthetic=true`, `license_review_status=SYNTHETIC`, `extracted_at` fixed at `2026-10-14T00:00:00Z` |
| Board | `board_id=synthetic-demo-1`, provider `synthetic-demo`, layout `1`, size `12x12`, geometry NORMALIZED 1.0 x 1.0, `y_axis=UP`, angles `[20, 30, 40, 45, 50]` |
| Holds | 144 on a 12x12 grid, `h001`..`h144` (`h{row*12+col+1}`, row 0 = bottom), `x=(col+0.5)/12`, `y=(row+0.5)/12` rounded to 3 decimals, `geometry_meta={"row","col"}` |
| Routes | `syn-route-0001`..`syn-route-0300`; 4–25 placements each: 1–2 START (rows 0–1), HAND holds in rows 2–10, 1–2 FINISH (row 11), 0–4 FOOT (rows 0–5); `placement_order` is the list index; names from a word list, `setter_pseudonym` from a fixed pseudonym list or null (~20%), 0–2 tags from `crimpy, slopers, dynamic, technical, powerful, balancy` |
| Variants | 1–3 distinct angles per route from the board's angles (609 variants in the seed-42 file) |
| sha256 of the file | `c320f15f4413a56901fd2928e24fbd5bf00748dc93f1986f481fb723b7738f7e` (pinned in `ml/tests/test_synthetic.py`) |

## The toy grade function (documented so nobody mistakes it for a model)

For each variant, with `rows` the grid rows of the route's placements and `hand_count` the number
of HAND placements:

```
grade = 10
      + 0.6 * (max(rows) - min(rows))          # vertical span in cells
      + 0.9 * max(0, 8 - hand_count)           # "fewer hand holds = bigger moves" penalty
      + angle_deg / 10
      + N(0, 1)                                # Gaussian noise
grade_numeric = round(clip(grade, 10, 33), 1)
```

About 5% of variants are left ungraded (`grade_numeric: null`, `grade_observation_count: 0`;
27 of 609 in the seed-42 file). Graded variants get `grade_observation_count` from a clipped
exponential with mean 40 (range 1..400). About 3% of variants are `validation_status: RETIRED`
(19 of 609), the rest `ACTIVE`. The coefficients were chosen by hand to land in a plausible
range of the numeric grade axis; they were not fitted to any data.

## Determinism and format

- All randomness comes from `random.Random(seed)`; nothing reads the clock, environment or
  hash-randomised iteration order. Same `--seed` and `--routes` → byte-identical file.
- Each line is written as RFC 8785 canonical JSON (keys sorted by UTF-16 code units, no
  whitespace, ECMAScript number formatting: `1.0` → `1`, `0.0` → `0`). The generator's
  `canonical_json` was cross-checked line by line against the Node reference canonicaliser in
  `test/contracts/jcs.test.cjs` (302/302 lines identical).
- `manifest.content_sha256` follows the rule in `contracts/catalog/README.md`: records are
  built first, hashed, and only then is the manifest written as line 1.

## Regenerate

```sh
make synthetic-board
# equivalent, from the repo root:
uv run --directory ml --no-project python -I datasets/synthetic/generate.py \
  --seed 42 --routes 300 --out datasets/synthetic/fixtures/demo-board-v1.jsonl
```

The committed fixture must come out unchanged (`git status --short ml` is empty). Run as a
script rather than `python -I -m datasets.synthetic.generate`: `-I` implies `-P`, which keeps the
working directory off `sys.path`, so the `-m` form fails with `ModuleNotFoundError`. Without `-I`,
`python -m datasets.synthetic.generate` from `ml/` also works (the package has an `__init__.py`).

If you change the generator on purpose, regenerate, then update `PINNED_SHA256` in
`ml/tests/test_synthetic.py` and the hash/size in this file, and say so in the commit message.

Tests: `uv run --directory ml --no-project --with pytest==9.1.1 pytest tests/test_synthetic.py -q`
(standard library only). Schema validation of the fixture runs in
`npm --prefix test/contracts test` (`catalog.test.cjs`).
