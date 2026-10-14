# Dataset manifests (BC-0002, Gate D0)

A manifest pins exactly which bytes a model, importer or evaluation was built from.
Manifests contain only hashes and aggregate counts. They never contain route rows,
`frames` strings, setter names or usernames. Raw data is never tracked in git: the only
raw location is `data/raw/` (gitignored), placed there by the human.

## Current status

| Manifest | Status | Meaning |
|---|---|---|
| `manifests/kilter-PENDING.json` | `BLOCKED_SQLITE_ABSENT` | sqlite not present at `data/raw/kilter_splits.sqlite`; hash, size and counts are `null`. Holds the HF revision and the HF-API-reported LFS sha256/size to check a download against. |

No measured manifest exists yet. Do not create a `kilter-<sha>.json` by hand.

## Format

```json
{
  "source_url": "https://huggingface.co/datasets/Vilin97/KilterBoard",
  "hf_revision": "<40-hex HF dataset commit the file was downloaded from>",
  "file": "kilter_splits.sqlite",
  "sha256": "<64-hex sha256 of the local file, computed by verify_manifest.py>",
  "bytes": 0,
  "tables": {"<table name>": 0},
  "uuid_counts": {"kilter_train": 0, "kilter_val": 0, "kilter_test": 0},
  "split_uuid_overlap": {"kilter_train&kilter_val": 0, "kilter_train&kilter_test": 0, "kilter_val&kilter_test": 0},
  "upstream_reported": {"lfs_sha256": "<from HF API>", "size_bytes": 0, "fetched_at": "...", "note": "..."},
  "extracted_at": "<UTC ISO-8601 time the manifest was written>",
  "license_review_status": "LOCAL_RESEARCH_ONLY",
  "status": "MEASURED"
}
```

Field rules:

- `sha256`, `bytes`, `tables`, `uuid_counts`, `split_uuid_overlap` are written only by
  `verify_manifest.py --write`; never typed by hand.
- `tables` is `COUNT(*)` for every non-internal table in `sqlite_master`.
- `uuid_counts` is `COUNT(DISTINCT uuid)` per split table (rows are keyed `(uuid, angle)`,
  so rows > distinct uuids is expected).
- `split_uuid_overlap` is the pairwise `INTERSECT` count; every value must be `0` or
  `--write` refuses to emit a manifest.
- `upstream_reported` values are what the Hugging Face API said, not a local measurement.
  `--write --template` refuses to write if the local sha256/size disagree with them.
- `license_review_status` stays `LOCAL_RESEARCH_ONLY` until the human signs the rights
  table in `docs/data-source-audit.md`. A manifest is not a licence.
- File name: `manifests/kilter-<first 12 hex of sha256>.json` (chosen by the script).

## Regenerate / verify

Requires only the Python standard library. Run with `-I` (isolated mode) from the repo root.

```sh
# 1. (human) download the pinned revision to the gitignored raw location
mkdir -p data/raw
curl -L --fail -o data/raw/kilter_splits.sqlite \
  https://huggingface.co/datasets/Vilin97/KilterBoard/resolve/8a5a0f11675494c3615c2c96815af6bde93235f9/kilter_splits.sqlite

# 2. write the measured manifest (checks against HF-reported sha256/size, refuses on split overlap)
python3 -I ml/datasets/verify_manifest.py data/raw/kilter_splits.sqlite \
  --write ml/datasets/manifests --template ml/datasets/manifests/kilter-PENDING.json

# 3. verify at any later time (prints OK, or MISMATCH plus a diff; exit 1)
python3 -I ml/datasets/verify_manifest.py data/raw/kilter_splits.sqlite ml/datasets/manifests/kilter-*.json
```

Exit codes: `0` OK, `1` mismatch or split overlap, `2` sqlite/manifest missing or unreadable.
The sqlite is opened with `mode=ro`; the script does not modify it (covered by
`ml/tests/test_verify_manifest.py::test_sqlite_is_not_modified`).

Tests: `uv run --directory ml --no-project --with pytest==9.1.1 pytest tests/test_verify_manifest.py -q`
(synthetic fixtures only).
