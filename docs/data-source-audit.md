# Data-source audit: Kilter Board L1-S10 (BC-0002, Gate D0)

| Field | Value |
|---|---|
| Ticket | BC-0002 (Gate D0); feeds BC-0014, BC-0103, BC-0104, BC-0201 |
| Author | Claude Code `data-auditor` |
| Audit date | 2026-10-14 |
| Gate D0 status | **PASSED for local research use (2026-10-18).** File verified against the pinned revision; counts, split disjointness and personal columns measured (section 0); upstream (Aurora) rights still unknown, so nothing derived leaves the machine except the Modal exception below |
| Human sign-off of the rights table (section 7) | **SIGNED 2026-10-18** by the repo owner (Joshua Wu), recorded verbatim in section 7 |

Evidence rule for this document: every number sits next to the command that produced
it and its output. Anything not measured says "not measured". Values from the dataset card
or the Hugging Face (HF) API are labelled as such: they are what the publisher states, not
what this audit observed in the file.

## 0. Measured on 2026-10-18 (supersedes the "not measured" notes below)

The human placed the file at `data/raw/kilter_splits.sqlite` (copied from a local HF clone at
revision `8a5a0f11675494c3615c2c96815af6bde93235f9`). Commands run from the repo root; the
sqlite was opened read-only and only aggregates were printed.

```text
$ shasum -a 256 data/raw/kilter_splits.sqlite
0a0bc5b1bed12c72898f692e2bdd6a05f35730af82dda8538f89d58a90989bc2  data/raw/kilter_splits.sqlite
$ stat -f %z data/raw/kilter_splits.sqlite
123748352
$ python3 -I ml/datasets/verify_manifest.py data/raw/kilter_splits.sqlite --write ml/datasets/manifests --template ml/datasets/manifests/kilter-PENDING.json
WROTE ml/datasets/manifests/kilter-0a0bc5b1bed1.json
$ python3 -I ml/datasets/verify_manifest.py data/raw/kilter_splits.sqlite ml/datasets/manifests/kilter-0a0bc5b1bed1.json
OK
```

| Table | Rows | Distinct uuid |
|---|---|---|
| kilter_train | 190439 | 116125 |
| kilter_val | 23676 | 14515 |
| kilter_test | 23787 | 14517 |
| holes / placements / placement_roles / difficulty_grades | 3294 / 3773 / 30 / 39 | — |

Findings that BC-0103 and BC-0201 must act on:

1. **uuid splits are disjoint** (0 shared uuids for every pair), but **identical hold sets
   cross splits**: 228 distinct `frames` strings appear in both train and val, 228 in both
   train and test, 24 in both val and test. Inside train, 885 `frames` values belong to more
   than one uuid. BC-0201 must deduplicate by a canonical hold-set hash before evaluation
   (drop or reassign the cross-split copies) and report the counts.
2. **Target duplicate:** `difficulty_average` equals `difficulty_numeric` on all 190439 train
   rows. It is the label, not a feature. Also post-hoc and excluded from features:
   `quality_average`, `ascensionist_count`, `benchmark_difficulty`, `fa_at`, `boulder_grade`,
   `route_grade`, `is_nomatch`.
3. **Noisy labels:** 118178 of 190439 train rows have `ascensionist_count < 3`. Grade
   consensus on those rows is weak; BC-0203 reports metrics overall and on a
   minimum-ascents subset, and the threshold is chosen on validation only.
4. **Card filter is not exact:** 41 rows across all splits have `difficulty_numeric > 30.5`
   (max 31.0), against the card's "≤ 30.5" filter. Kept, documented.
5. **Personal columns present:** `fa_username`, `setter_id`, `setter_username`,
   `beta_usernames`, `beta_links`, and free-text `name` and `description`. The extractor
   (BC-0103) drops all of them; they never reach catalog JSONL, features, Modal or logs.
6. **Multi-product reference tables:** `holes` spans 7 products and `placements` 8 layouts;
   the extractor joins on layout 1 / sets {1, 20} only.
7. **Shape:** one row per (uuid, angle); 15 angles from 0 to 70 (40 degrees is the largest
   group, 55416 train rows); 1 to 15 angles per uuid, mean 1.64. `frames` is a string of
   `p<placement_id>r<role_id>` tokens; roles 12–15 are start, middle, finish, foot.
   `created_at` spans 2018-04-22 to 2025-07-12.

## Blockers (historical, 2026-10-14; items 1 and 3 resolved on 2026-10-18)

1. **sqlite absent.** The data file is not on this machine, so nothing in sections 3 to 6
   has been measured.

   ```text
   $ ls data/raw/
   ls: data/raw/: No such file or directory

   $ python3 -I ml/datasets/verify_manifest.py data/raw/kilter_splits.sqlite ml/datasets/manifests/kilter-PENDING.json; echo "exit=$?"
   ERROR: sqlite not found: data/raw/kilter_splits.sqlite
   exit=2
   ```

2. **Aurora Climbing rights: UNKNOWN-NEEDS-HUMAN.** The route content comes from the
   Kilter app (Aurora Climbing) through BoardLib. Nothing located grants permission to
   import, redistribute, derive from or train on it. The HF `license: mit` tag is a
   statement by the HF uploader about the HF repository. It is **not** evidence that
   Aurora Climbing consented, and this audit does not treat it as such. See section 7.

3. **Human actions required** (an agent may not do these):
   - Download the pinned file from HF to `data/raw/` (section 10, step 1). Agents may not
     download, scrape or run a BoardLib sync.
   - Read and sign, or amend, the rights table in section 7.
   - Optional, and the only path to publishing anything derived: get written clarity
     from Aurora Climbing.

Until 1 and 3 are done, downstream tickets that read Kilter data (BC-0014 table fill,
BC-0103 importer run, BC-0201 split/leakage audit, Model A training) stay blocked. The
synthetic demo board (BC-0104) is not blocked (section 9).

---

## 1. Source and access path

| Item | Value | Evidence |
|---|---|---|
| Dataset | `Vilin97/KilterBoard` on Hugging Face | https://huggingface.co/datasets/Vilin97/KilterBoard |
| Title on card | "Kilter Board L1-S10 (clean) — train / val / test" | dataset card, fetched below |
| Stated upstream | "Data source: **BoardLib** repo + Kilter app." | dataset card |
| BoardLib | `lemeryfertitta/BoardLib`, GitHub licence MIT (covers BoardLib **code**, not the data it fetches) | GitHub API output below |
| How BoardLib gets data | Syncs Aurora's shared app SQLite through an undocumented API using a board account (planning lead finding, `planning/09-prd-critique.md` §2 D1; not re-verified in this audit) | not re-verified |
| HF gating | `"gated": false`, `"private": false` | HF API output below |
| HF licence | `cardData.license: "mit"`, tag `license:mit`. Card has no licence text and no statement about upstream permission | card + API |
| Access path for this project | The human downloads the single file at the pinned revision into `data/raw/` (gitignored). No agent download, scraping or BoardLib sync | section 10 |

Fetch commands (run 2026-10-14; fetched content handled as untrusted data in an isolated
scratch directory, not committed):

```text
$ date -u +%FT%TZ
2026-10-14T17:20:39Z
$ curl -sS -L --max-time 30 -o README.md -w '%{http_code} %{size_download}\n' https://huggingface.co/datasets/Vilin97/KilterBoard/raw/main/README.md
200 674
$ curl -sS -L --max-time 30 -o api.json -w '%{http_code} %{size_download}\n' https://huggingface.co/api/datasets/Vilin97/KilterBoard
200 1291
$ curl -sS -L --max-time 30 -o tree.json -w '%{http_code} %{size_download}\n' "https://huggingface.co/api/datasets/Vilin97/KilterBoard/tree/main"
200 502
$ curl -sS -L --max-time 30 -o README.pinned.md -w '%{http_code} %{size_download}\n' https://huggingface.co/datasets/Vilin97/KilterBoard/raw/8a5a0f11675494c3615c2c96815af6bde93235f9/README.md
200 674
$ shasum -a 256 README.md README.pinned.md
369f25782aa57a02c387e272f25c026663f28ffac52b3905386ba38dc828adac  README.md
369f25782aa57a02c387e272f25c026663f28ffac52b3905386ba38dc828adac  README.pinned.md
$ curl -sS --max-time 30 https://api.github.com/repos/lemeryfertitta/BoardLib | python3 -I -c "...print full_name, license.spdx_id, pushed_at, archived, default_branch"
{'full_name': 'lemeryfertitta/BoardLib', 'license': 'MIT', 'pushed_at': '2026-03-17T20:52:36Z', 'archived': False, 'default_branch': 'main'}
```

At fetch time `main` resolved to commit `8a5a0f11…` (the card at `main` and at the pinned
commit hash to the same value).

Dataset card statements, quoted verbatim from the fetched README:

> Filtered problems for **layout 1, size 10, sets {1, 20}**.
> Rows are keyed by `(uuid, angle)`; splits are **80 / 10 / 10 %** on UUIDs.
>
> Filters: `ascensionist_count > 0`; `difficulty_numeric ≤ 30.5` (~ V13); `num_holds ≥ 3` and `≤ 50`
>
> Data source: **BoardLib** repo + Kilter app.

Aurora Climbing terms of service / API terms: **not located.** The planning lead's web
search (2026-10-14) did not find them, and this audit did not search further. Absence of
located terms is not permission.

## 2. Hash, size, HF commit

| Item | HF-reported (not locally verified) | Locally measured |
|---|---|---|
| HF dataset commit | `8a5a0f11675494c3615c2c96815af6bde93235f9` (API `sha`; also `x-repo-commit` header) | n/a |
| HF `lastModified` / `createdAt` | `2025-07-15T04:22:38.000Z` / `2025-07-15T02:37:56.000Z` | n/a |
| File | `kilter_splits.sqlite` (only data file; other files are `.gitattributes`, `README.md`) | **not measured** (file absent) |
| sha256 | `0a0bc5b1bed12c72898f692e2bdd6a05f35730af82dda8538f89d58a90989bc2` (LFS oid from tree API; same value in `x-linked-etag`) | **not measured** |
| Size | `123748352` bytes (tree API `size`, `x-linked-size`, `content-length`) | **not measured** |
| HF `size_categories` tag | `100K<n<1M` (publisher's tag; unit not stated) | **not measured** |

Evidence (JSON trimmed to the relevant fields):

```text
$ python3 -I -m json.tool api.json
    "sha": "8a5a0f11675494c3615c2c96815af6bde93235f9",
    "lastModified": "2025-07-15T04:22:38.000Z",
    "gated": false,
    "tags": ["language:en", "license:mit", "size_categories:100K<n<1M", "region:us"],
    "siblings": [{"rfilename": ".gitattributes"}, {"rfilename": "README.md"}, {"rfilename": "kilter_splits.sqlite"}],
    "createdAt": "2025-07-15T02:37:56.000Z",
    "usedStorage": 123748352
$ python3 -I -m json.tool tree.json
    {"type": "file", "oid": "2582cf2301963699d48d31ea1ec8ee2143045f91", "size": 123748352,
     "lfs": {"oid": "0a0bc5b1bed12c72898f692e2bdd6a05f35730af82dda8538f89d58a90989bc2", "size": 123748352, "pointerSize": 134},
     "path": "kilter_splits.sqlite"}
$ curl -sS -I -L --max-time 30 https://huggingface.co/datasets/Vilin97/KilterBoard/resolve/8a5a0f11675494c3615c2c96815af6bde93235f9/kilter_splits.sqlite | grep -i -E '^(HTTP|content-length|x-linked-size|x-linked-etag|etag|x-repo-commit)'
HTTP/2 302
content-length: 1054
x-repo-commit: 8a5a0f11675494c3615c2c96815af6bde93235f9
x-linked-size: 123748352
x-linked-etag: "0a0bc5b1bed12c72898f692e2bdd6a05f35730af82dda8538f89d58a90989bc2"
HTTP/2 200
etag: "94b352149ced1fd39b294c025a2284dfd8d0c16eebd6318171e82fb52d15d256"
content-length: 123748352
```

The HEAD request downloaded headers only, not the file. These upstream values are stored
in `ml/datasets/manifests/kilter-PENDING.json` under `upstream_reported`.
`verify_manifest.py --write --template …` refuses to write a manifest if the downloaded
file's sha256 or size disagrees with them.

## 3. Schema

**From dataset card, unverified.** `.schema` has not been run.

| Table | Stated content (card) | Columns known | Source of column knowledge |
|---|---|---|---|
| `kilter_train` | filtered climbs | `uuid`, `angle` (key); `ascensionist_count`, `difficulty_numeric`, `num_holds` appear in filter text, but whether they are stored columns is unknown | dataset card, unverified |
| `kilter_val` | filtered climbs | as above | dataset card, unverified |
| `kilter_test` | filtered climbs | as above | dataset card, unverified |
| `difficulty_grades` | reference look-up | unknown | dataset card lists table name only |
| `placements` | reference look-up | unknown | dataset card lists table name only |
| `placement_roles` | reference look-up | unknown | dataset card lists table name only |
| `holes` | reference look-up | unknown | dataset card lists table name only |

Route encoding expected by planning (`planning/09-prd-critique.md` §2 D4, from BoardLib's
schema): a `frames` string of `p<placement_id>r<role_id>` tokens. Resolution is
`placements → holes` for (x, y) and `placement_roles` for role names. **Unverified for
this file.** Also not known: whether `frames`, `layout_id`, `setter_*`, `name`,
`description`, `created_at`, `quality_average` or similar columns survived the uploader's
filtering.

Commands that WILL be run (NOT YET EXECUTED; syntax-checked only against a synthetic
fixture):

```sh
sqlite3 -readonly data/raw/kilter_splits.sqlite '.tables'
for t in kilter_train kilter_val kilter_test difficulty_grades placements placement_roles holes; do
  sqlite3 -readonly data/raw/kilter_splits.sqlite ".schema $t"
done
```

## 4. Measured counts

**Not measured** (sqlite absent). The card's "80 / 10 / 10 %" and the HF tag
`100K<n<1M` are publisher claims and are not counts.

| Quantity | Value | Command (NOT YET EXECUTED) |
|---|---|---|
| Rows per split | not measured | query A |
| Unique `uuid` per split | not measured | query A; also `verify_manifest.py` `uuid_counts` |
| Unique angles per split / overall | not measured | query A, query C |
| Routes (uuids) with more than one angle | not measured | query B |
| Duplicate `(uuid, angle)` keys | not measured | query D (must be 0 if the card's keying claim holds) |
| Row count of every table | not measured | `verify_manifest.py` `tables` |
| `frames` values not matching `p<n>r<n>…` | not measured | query E |

```sh
DB=data/raw/kilter_splits.sqlite
# A: rows, unique uuids, unique angles per split
sqlite3 -readonly -header -column "$DB" "
SELECT 'train' AS split, COUNT(*) AS rows_, COUNT(DISTINCT uuid) AS uuids, COUNT(DISTINCT angle) AS angles FROM kilter_train
UNION ALL SELECT 'val', COUNT(*), COUNT(DISTINCT uuid), COUNT(DISTINCT angle) FROM kilter_val
UNION ALL SELECT 'test', COUNT(*), COUNT(DISTINCT uuid), COUNT(DISTINCT angle) FROM kilter_test;"
# B: uuids with >1 angle (all splits)
sqlite3 -readonly "$DB" "SELECT 'multi_angle_uuids', COUNT(*) FROM (SELECT uuid FROM (SELECT uuid, angle FROM kilter_train UNION ALL SELECT uuid, angle FROM kilter_val UNION ALL SELECT uuid, angle FROM kilter_test) GROUP BY uuid HAVING COUNT(DISTINCT angle) > 1);"
# C: distinct angle values overall
sqlite3 -readonly "$DB" "SELECT 'distinct_angles', group_concat(angle) FROM (SELECT DISTINCT angle FROM (SELECT angle FROM kilter_train UNION SELECT angle FROM kilter_val UNION SELECT angle FROM kilter_test) ORDER BY angle);"
# D: duplicate (uuid, angle) keys
sqlite3 -readonly "$DB" "SELECT 'dup_uuid_angle_keys', COUNT(*) FROM (SELECT uuid, angle FROM (SELECT uuid, angle FROM kilter_train UNION ALL SELECT uuid, angle FROM kilter_val UNION ALL SELECT uuid, angle FROM kilter_test) GROUP BY uuid, angle HAVING COUNT(*) > 1);"
# E: frames format conformance (count only; prints no route content)
sqlite3 -readonly "$DB" "SELECT 'frames_nonconforming', COUNT(*) FROM (SELECT frames FROM kilter_train UNION ALL SELECT frames FROM kilter_val UNION ALL SELECT frames FROM kilter_test) WHERE frames IS NULL OR frames NOT GLOB 'p[0-9]*r[0-9]*';"
# manifest (hash, size, table rows, uuid counts, overlap) in one step
python3 -I ml/datasets/verify_manifest.py "$DB" --write ml/datasets/manifests --template ml/datasets/manifests/kilter-PENDING.json
```

Missing-data analysis: **not run.** The command prints only column names and counts,
never values. Save the snippet outside the repo and run it with `-I`:

```sh
mkdir -p /tmp/bc0002 && cat > /tmp/bc0002/nulls.py <<'PY'
import sqlite3, sys
from pathlib import Path
db = sqlite3.connect(Path(sys.argv[1]).resolve().as_uri() + "?mode=ro", uri=True)
for t in ("kilter_train", "kilter_val", "kilter_test"):
    total = db.execute(f'SELECT COUNT(*) FROM "{t}"').fetchone()[0]
    for (col,) in db.execute(f"SELECT name FROM pragma_table_info('{t}')"):
        n = db.execute(f'SELECT COUNT(*) FROM "{t}" WHERE "{col}" IS NULL OR "{col}" = \'\'').fetchone()[0]
        print(f"{t}\t{col}\tnull_or_empty={n}\trows={total}")
PY
python3 -I /tmp/bc0002/nulls.py data/raw/kilter_splits.sqlite
```

Of all the queries above, A to E and the PII listing in section 6 were run on a synthetic
scratch fixture to check their syntax, as was `nulls.py`. The section 5 overlap queries
were also run on that fixture. The fixture was outside the repo and had fake uuids such
as `synth-a`. It produced no Kilter numbers.

## 5. Split disjointness and leakage audit

**Not run** (sqlite absent).

The required check: the card says splits are by UUID. Because rows are keyed
`(uuid, angle)`, a route must not appear in two splits through different angles
(PRD §6.4). The three pairwise counts must all be `0`:

```sh
sqlite3 -readonly data/raw/kilter_splits.sqlite "
SELECT 'train&val',  COUNT(*) FROM (SELECT uuid FROM kilter_train INTERSECT SELECT uuid FROM kilter_val);
SELECT 'train&test', COUNT(*) FROM (SELECT uuid FROM kilter_train INTERSECT SELECT uuid FROM kilter_test);
SELECT 'val&test',   COUNT(*) FROM (SELECT uuid FROM kilter_val   INTERSECT SELECT uuid FROM kilter_test);"
```

`verify_manifest.py` runs the same three queries (`split_uuid_overlap`). It refuses to
write a manifest, and fails verification, if any of them is non-zero. This is covered by
`ml/tests/test_verify_manifest.py::test_overlapping_splits_*`.

Further leakage checks belong to BC-0201 (listed here so they aren't lost; not run):

| Check | Why | Command / owner |
|---|---|---|
| Same `frames` string under different uuids across splits | A re-posted route with the same geometry under a new uuid would leak geometry into test | `SELECT COUNT(*) FROM (SELECT frames FROM kilter_train INTERSECT SELECT frames FROM kilter_test);` and the train/val and val/test pairs; then a canonical (sorted-token) hash in BC-0201 |
| Popularity/consensus columns excluded from Model A features | `ascensionist_count`, `quality_average` and any community grade are post-hoc, not geometry (`planning/09-prd-critique.md` D3) | BC-0201 column-exclusion assert |
| Grade as input | `difficulty_numeric` is the Model A label; it must never be a feature | BC-0201 |
| Test set sealed | PRD §7.2: stop tuning after the test set has been inspected | ml-researcher |

## 6. PII and personal columns

**Not inspected** (sqlite absent).

Expected candidate columns, if the uploader kept them from Aurora's `climbs` table
(unverified; BoardLib-derived expectation, not observed):

| Candidate column | Personal? | Planned handling |
|---|---|---|
| `setter_username`, `setter_id` | Yes: identifies a real person (the route setter) | Never imported, logged or committed. Drop at import (BC-0103). |
| `name`, `description` (route name and free text) | Possibly: free text can contain names or handles | Do not import free text in v1. Display uses uuid or a generated label. |
| `created_at` | Quasi-identifying when combined with setter | Do not import. |
| `ascensionist_count`, `quality_average` | Aggregate, not personal; but leakage-prone (section 5) | Ranker fallback only, never a Model A feature. |

User-level attempt or logbook rows: **none expected.** The card describes only "filtered
climbs" and reference look-ups, and no table with user ids or attempt timestamps is
listed. To be confirmed by the listing below. **This dataset is a route catalog, not
longitudinal climber attempts** (section 8).

Command that WILL be run (NOT YET EXECUTED). It lists column **names** only and is
deliberately over-inclusive: `_` is a LIKE wildcard, so `%_id` also matches `uuid`.

```sh
sqlite3 -readonly -header -column data/raw/kilter_splits.sqlite "
SELECT m.name AS tbl, p.name AS col, p.type FROM sqlite_master m JOIN pragma_table_info(m.name) p
WHERE m.type='table' AND (lower(p.name) LIKE '%user%' OR lower(p.name) LIKE '%setter%' OR lower(p.name) LIKE '%name%'
  OR lower(p.name) LIKE '%email%' OR lower(p.name) LIKE '%_id' OR lower(p.name) LIKE '%created%' OR lower(p.name) LIKE '%description%');"
```

Logging rule for whoever runs it: paste column names and counts into this doc, never
values from personal columns.

## 7. Rights classification

Classification keys: PERMITTED, NOT PERMITTED, UNKNOWN-NEEDS-HUMAN.
"Upstream (Aurora)" is the legal question. "Project default" is what this project does
until the human signs. A project default of NOT PERMITTED is a policy choice made because
rights are unknown. It is not a finding that the use is unlawful.

| Use | Upstream (Aurora Climbing) | HF uploader (MIT tag) | Project default until signed | Reasoning |
|---|---|---|---|---|
| (a) Local research import (human downloads; data stays in `data/`, gitignored, single machine) | **UNKNOWN-NEEDS-HUMAN** | MIT tag suggests the uploader intends reuse, but it covers only what the uploader had the right to license | **UNKNOWN-NEEDS-HUMAN.** Recommended conditional default: local-only, gated, after the human signs below | Lowest-risk use, but no upstream permission found. `planning/08-unanswered-questions.md` Q2 (does the human accept local-only use?) has no recorded answer. |
| (b) Redistribution of raw data (sqlite, rows, frames, route names) in git, releases, images or a public demo | **UNKNOWN-NEEDS-HUMAN** | as above | **NOT PERMITTED** | Content originates in Aurora's app via an undocumented API; Aurora terms unlocated. Re-hosting is the highest-risk use. |
| (c) Derived geometry (parsed hold sets, coordinates from `holes`/`placements`, route embeddings, parquet extracts) leaving the local machine | **UNKNOWN-NEEDS-HUMAN** | as above | **NOT PERMITTED** | Derived geometry reproduces route content and board layout. Treat as redistribution. |
| (d) Published model weights or metrics trained on this data (public repo, model hub, demo backend) | **UNKNOWN-NEEDS-HUMAN** | as above | **NOT PERMITTED** without written human sign-off | Weights can memorize or encode the catalog; publishing is irreversible. Local experiments are covered by (a) only. |
| (e) Demo use (public or recorded demo, clean-clone `make seed-demo`) | **UNKNOWN-NEEDS-HUMAN** for Kilter content | as above | **NOT PERMITTED** for Kilter content; **PERMITTED** for the synthetic demo board (BC-0104), which is generated by this project and contains no Kilter data | A clean clone must work without Kilter data (`planning/09-prd-critique.md` D1). |

Explicit non-inferences:

- The HF `license: mit` tag is not evidence that Aurora Climbing granted anything.
- BoardLib's MIT licence covers BoardLib's source code, not data fetched through it.
- HF `gated: false` (anyone can download) is not permission either.

**Human sign-off** (to be completed by the human, not an agent):

```text
[x] I accept use (a) local research import under the conditions above.     Signed: Joshua Wu (repo owner)  Date: 2026-10-18
[x] I confirm (b), (c), (d) stay NOT PERMITTED until written clarity from Aurora Climbing is obtained.
[x] I have NOT contacted Aurora Climbing. Reference: none
```

Recorded from the owner's chat answers on 2026-10-18 ("Yes i agree for local research use,
yes i confirm, No, no, I allow modal use"). The second "no" answers Q4: no consented real
attempt histories exist beyond the owner's own, so Gate D1 stays NO-GO by default.

**Modal exception (owner decision, 2026-10-18).** The owner allows Modal for training and
evaluation runs on this data. That is a transfer to a third-party processor, so it is a
narrow exception to (c) and (d), not a lifting of them: the upload is the extracted
training set with every personal column from section 0 item 5 removed; it lives only in a
private Modal volume or the run's ephemeral storage; weights, embeddings and per-route
predictions come back to `data/models/` and are never published, shared or pushed to a
hub; metrics may enter the evidence ledger, labelled with the dataset manifest id. ADR-0007
holds the operational rules.

**Safe alternative if the human declines (a), or if rights stay ambiguous:** stop all
Kilter work. Ship the synthetic demo board (BC-0104) for product, demo and load testing.
Train Model A, if at all, only on data with clear rights, for example the human's own
board-set routes or another dataset that passes a fresh Gate D0. Label every result
accordingly.

## 8. Limitations and representativeness

These come from the card's stated filters and the project's planning findings. None are
measured yet.

- **Route catalog, not attempts.** The card says rows are "filtered climbs" keyed
  `(uuid, angle)`. Nothing indicates per-user, timestamped attempt histories, and
  `ascensionist_count` is an aggregate. The data can support Model A
  (geometry → community grade). It cannot support Model B (personal send probability);
  Gate D1 governs that (PRD §2.2, §7.1).
- **Labels are community averages.** `difficulty_numeric` is described in planning as a
  fractional average of user-submitted grades (`planning/09-prd-critique.md` D5). It
  carries rater variance and popularity effects, and it is not ground-truth physical
  difficulty. Its distribution is not measured (BC-0014).
- **Selection bias from filters (card):** `ascensionist_count > 0` drops never-repeated
  routes and biases toward popular ones. `difficulty_numeric ≤ 30.5` (card: "~ V13")
  truncates the hardest routes. `num_holds` between 3 and 50 drops extreme hold counts.
- **Single board configuration:** layout 1, size 10, sets {1, 20} only. Results do not
  generalize to other Kilter layouts, sizes or hold sets, or to other boards, without new
  data. The "unseen board setup" holdout in PRD §6.4 is not possible with this file.
- **Angle coverage:** unknown (section 4, query C). A route appears at several angles
  with possibly different grades, so the split must stay at uuid level.
- **Snapshot age:** the HF commit is dated 2025-07-15. Routes and grades set or re-graded
  after the uploader's BoardLib sync are absent, and the sync date itself is not stated on
  the card.
- **Split provenance:** the 80/10/10 split was made by the uploader by an unstated method
  (random? seed?). Section 5 verifies disjointness, not randomness or stratification.
- **Duplicate geometry:** the same hold set may exist under different uuids. Not checked
  (section 5).
- **Representativeness of climbers:** grades reflect the population of Kilter app users
  who log ascents. Their demographics, skill and location are unknown.

## 9. Fallback: synthetic demo board

If Gate D0 is not passed, or the human declines (a), the project uses the **synthetic
demo board** (BC-0104):

- Board geometry and routes are generated by project code. They are labelled
  **synthetic**, contain no Kilter placements, frames or grades, and are safe to commit
  and demo.
- Grades on synthetic routes are generated, so no model accuracy claim can be made on
  them. They exist for UI, API, streaming and replay testing only.
- Clean-clone default: `make seed-demo` (BC-0120) loads the synthetic board, never
  Kilter.
- Model A on Kilter stays a local research activity under (a), with weights unpublished.

## 10. How to unblock (human runs these, in order)

None of the following has been executed. Run them from the repo root.

```sh
# 1. Download the pinned revision (human only; ~124 MB per HF-reported size) to the gitignored raw location
mkdir -p data/raw
curl -L --fail -o data/raw/kilter_splits.sqlite \
  https://huggingface.co/datasets/Vilin97/KilterBoard/resolve/8a5a0f11675494c3615c2c96815af6bde93235f9/kilter_splits.sqlite

# 2. Hash and size; must equal the HF-reported values in section 2
shasum -a 256 data/raw/kilter_splits.sqlite      # expect 0a0bc5b1bed12c72898f692e2bdd6a05f35730af82dda8538f89d58a90989bc2
stat -f %z data/raw/kilter_splits.sqlite          # macOS; expect 123748352 (Linux: stat -c %s)

# 3. Write the measured manifest (refuses on sha/size mismatch or split overlap)
python3 -I ml/datasets/verify_manifest.py data/raw/kilter_splits.sqlite \
  --write ml/datasets/manifests --template ml/datasets/manifests/kilter-PENDING.json

# 4. Verify it reproduces (expect: OK)
python3 -I ml/datasets/verify_manifest.py data/raw/kilter_splits.sqlite ml/datasets/manifests/kilter-*[0-9a-f].json

# 5. Schema, counts, disjointness, PII column listing, missing data: sections 3, 4, 5, 6

# 6. Confirm nothing under data/ is tracked or staged
git status --short data/ ; git ls-files data/     # both must print nothing
```

Then hand back to `data-auditor` to replace every "not measured" with the command
output, remove `kilter-PENDING.json` (superseded), and re-request `security-reviewer`
review. Finally, the human signs section 7.
