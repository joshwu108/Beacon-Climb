# Data card — Model A dataset `model-a-100d08f359ca` (BC-0201)

**LOCAL RESEARCH ONLY.** Source: Hugging Face `Vilin97/KilterBoard` at revision `8a5a0f11`, sqlite sha256 `0a0bc5b1…` (`ml/datasets/manifests/kilter-0a0bc5b1bed1.json`). Rights: `docs/data-source-audit.md` section 7. Rows live only in `data/datasets/model-a-100d08f359ca/` (gitignored); this card and the manifest `ml/datasets/manifests/model-a-100d08f359ca.json` hold counts and hashes only. Every number below is copied from that manifest, produced by `make extract-kilter && make freeze-dataset` on 2026-10-19.

## What a row is

One graded climb at one wall angle on Kilter layout 1 (sets 1 and 20). Fields:

| Field | Use | Note |
|---|---|---|
| `holds` | feature | list of `{id, x, y, role}`; raw board coordinates (x −20…164, y 4…176), role START / HAND / FOOT / FINISH |
| `angle_deg` | feature | 0–70 in steps of 5 |
| `grade` | label | Aurora `difficulty_numeric` (10 ≈ 4a/V0 … 31 ≈ 8b+/V14), community consensus |
| `ascents` | metadata, **not a feature** | number of logged ascents; for weighting and the high-ascent subset only |
| `route_uuid`, `hold_set_hash` | metadata | joins and leakage checks |

Never present: route names, descriptions, setter or first-ascent usernames, beta links, quality ratings, benchmark flags (the extractor never reads them).

## Splits and leakage controls

- Official uuid splits from the dataset (80/10/10 by climb), re-asserted disjoint; all angles of a climb stay in one split.
- A climb's hold set is hashed over sorted `hold:role` pairs, angle ignored. A val or test climb whose hold set, or its left-right mirror image on the board, already appears in an earlier split is removed with all its angles. Precedence train → val → test.
- The test split is **sealed**: its sha256 is pinned in the manifest, and `ml/datasets/model_a/access.py` opens it only with a stated purpose and logs every opening to `test-access.log`.

| Removed from later splits | Climbs |
|---|---|
| `test_removed_exact_duplicate` | 255 |
| `val_removed_exact_duplicate` | 237 |
| `val_removed_mirror_duplicate` | 2 |

The extractor had already rejected 351 multi-frame rows (`ml/datasets/kilter/README.md`). No graded variant was dropped here.

## Counts

| Split | Rows (climb × angle) | Climbs | Rows with ≥ 3 ascents | File sha256 |
|---|---|---|---|---|
| train | 190186 | 116058 | 72089 | `47bc17c56e3a041b…` |
| val | 23106 | 14262 | 8495 | `60fb4dee76fe5416…` |
| test | 23149 | 14251 | 8496 | `cf8ae165a08b5ce6…` |

### Rows by angle

| Angle | train | val | test |
|---|---|---|---|
| 0° | 2385 | 289 | 268 |
| 5° | 1682 | 206 | 200 |
| 10° | 4072 | 487 | 508 |
| 15° | 6362 | 737 | 818 |
| 20° | 12210 | 1469 | 1508 |
| 25° | 9811 | 1224 | 1222 |
| 30° | 27977 | 3375 | 3473 |
| 35° | 12855 | 1575 | 1545 |
| 40° | 55383 | 6764 | 6738 |
| 45° | 27373 | 3381 | 3346 |
| 50° | 20069 | 2420 | 2358 |
| 55° | 3894 | 465 | 455 |
| 60° | 4086 | 496 | 486 |
| 65° | 623 | 57 | 60 |
| 70° | 1404 | 161 | 164 |

### Rows by grade (nearest integer difficulty, halves up)

| Difficulty | Grade | train | val | test |
|---|---|---|---|---|
| 10 | 4a/V0 | 3250 | 410 | 412 |
| 11 | 4b/V0 | 3706 | 450 | 484 |
| 12 | 4c/V0 | 3207 | 389 | 428 |
| 13 | 5a/V1 | 5076 | 651 | 705 |
| 14 | 5b/V1 | 6132 | 715 | 738 |
| 15 | 5c/V2 | 10829 | 1356 | 1358 |
| 16 | 6a/V3 | 13072 | 1625 | 1547 |
| 17 | 6a+/V3 | 13279 | 1682 | 1620 |
| 18 | 6b/V4 | 15631 | 1859 | 1924 |
| 19 | 6b+/V4 | 15309 | 1865 | 1856 |
| 20 | 6c/V5 | 17095 | 2075 | 2090 |
| 21 | 6c+/V5 | 15560 | 1896 | 1856 |
| 22 | 7a/V6 | 21056 | 2518 | 2574 |
| 23 | 7a+/V7 | 17606 | 2176 | 2045 |
| 24 | 7b/V8 | 10836 | 1270 | 1254 |
| 25 | 7b+/V8 | 7181 | 803 | 889 |
| 26 | 7c/V9 | 5745 | 714 | 693 |
| 27 | 7c+/V10 | 3284 | 393 | 414 |
| 28 | 8a/V11 | 1543 | 178 | 184 |
| 29 | 8a+/V12 | 607 | 61 | 61 |
| 30 | 8b/V13 | 152 | 16 | 9 |
| 31 | 8b+/V14 | 30 | 4 | 8 |

## Known limitations

- **Labels are noisy.** Most rows have fewer than 3 ascents; the grade is a weak consensus there. Report metrics overall and on a high-ascent subset whose threshold is chosen on validation.
- **Imbalanced angles and grades.** 40° dominates; V11 and harder are rare, so per-bin metrics there have wide intervals.
- **One board, one community.** Grades reflect Kilter app users and do not transfer to other boards or gyms without evidence.
- **Mirror check is position-based.** 512 of 692 holds have a hold at the exact mirrored position; climbs using the other 180 holds cannot be mirror-checked.
- **Within-split duplicates are kept.** Identical hold sets inside train are separate climbs and stay; they never cross into val or test.
- **Grade names come from the dataset's own `difficulty_grades` table**, because `contracts/catalog/grade-scale.v1.json` has no entries yet (BC-0014 blocked).
