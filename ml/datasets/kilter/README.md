# ml/datasets/kilter — Kilter extractor (BC-0103)

**LOCAL RESEARCH ONLY.** Rights: `docs/data-source-audit.md` section 7 (signed 2026-10-18).
Only this README and `extract.py` are tracked. Inputs and outputs live under `data/`
(gitignored) and are never committed, published or redistributed.

## Run

```sh
make extract-kilter     # needs data/raw/kilter_splits.sqlite placed by the human
```

The extractor refuses (exit 2) unless `--acknowledge-audit` is passed, the input manifest
(`ml/datasets/manifests/kilter-0a0bc5b1bed1.json`) says `LOCAL_RESEARCH_ONLY`, the sqlite
sha256 matches it, the audit carries the signed acceptance line, and `--out` is inside `data/`.

## Output (`data/catalog/kilter-<input sha12>/`)

| File | Content |
|---|---|
| `catalog.jsonl` | catalog v1 (`contracts/catalog/README.md`): manifest (`synthetic: false`, `LOCAL_RESEARCH_ONLY`), board `kilter-l1-s10`, one route per climb uuid with one variant per angle |
| `splits.json` | uuid → official split (`train`/`val`/`test`), for BC-0201; not part of the catalog |
| `extract-report.json` | counts, rejections by reason, role ids seen, role mapping, coordinate normalisation |

## Mapping decisions (measured on the pinned file, 2026-10-19)

- **Holds:** every placement of layout 1, sets 1 and 20 (692). `provider_hold_id` is
  `p<placement_id>`; `x`/`y` are normalised to [0, 1] over those placements
  (`x_min -20, span 184; y_min 4, span 172`, y up) and the raw values plus hole and set ids are
  kept in `geometry_meta`.
- **Roles are mapped by `placement_roles.name`, not id:** start → START, middle → HAND,
  finish → FINISH, foot → FOOT. Kilter climbs also reference role ids 20–31 (other Aurora
  products with the same four names), 1,276 tokens in all. Unknown names are rejected.
- **Rejected rows:** `frames_count > 1` (351 rows, a different multi-frame encoding). Other
  reasons (malformed frames, unknown placement or role, non-integer angle, uuid in two splits,
  duplicate angle) occur 0 times in the pinned file but are counted if they appear.
- **Duplicate tokens:** the same hold with the same role twice is kept once (2 tokens dropped);
  the same hold with two different roles keeps both.
- **Variants:** `grade_numeric` = `difficulty_numeric` rounded to 2 decimals;
  `grade_observation_count` = `ascensionist_count`; `is_listed = 0` → `RETIRED`.
- **Ignored columns:** `has_finish` is 0 on 237,763 of 237,902 rows although the frames carry
  finish holds, so it is not used. `created_at` has no time zone; UTC is assumed.
- **Personal and free-text columns are never read** (`name`, `description`, `setter_*`, `fa_*`,
  `beta_*`); routes carry `name: null`, `setter_pseudonym: null`, `tags: []`.

## Result on the pinned file

| Measure | Value |
|---|---|
| Rows read (train / val / test) | 190439 / 23676 / 23787 |
| Routes (train / val / test) | 116058 / 14501 / 14506 |
| Variants | 237551 |
| Catalog `content_sha256` | `e42c44c5b74d89b41d9d8ef71310f4ec18f004ab72cd6f57169c6a51a6d0d356` |

Contract check: `validateCatalogFile` from `test/contracts/catalog.test.cjs` accepts the file.
Tests: `ml/tests/test_kilter_extract.py` (synthetic sqlite only).
