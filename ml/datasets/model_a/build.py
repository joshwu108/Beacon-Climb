"""Freeze the Model A dataset from the Kilter catalog (BC-0201). LOCAL RESEARCH ONLY.

Input: the BC-0103 output directory ``data/catalog/kilter-<sha12>/`` (catalog.jsonl,
splits.json) and the sqlite (read-only, only for the ``difficulty_grades`` name table).

Output (gitignored) under ``data/datasets/model-a-<dataset id>/``:

* ``train.jsonl``, ``val.jsonl``, ``test.jsonl``: one row per graded (route, angle) with only
  the allowlisted fields of ``ROW_FIELDS``.
* ``dataset-report.json``: every count, the dedupe decisions and the per-file sha256.

Committed (hashes and counts only, never rows): ``ml/datasets/manifests/model-a-<id>.json``.

Leakage policy (planning BC-0201, audit section 0):
1. Official uuid splits are kept and re-asserted disjoint.
2. A route's hold set is hashed over sorted ``<hold>:<role>`` pairs, ignoring angle. A val or
   test route whose hold set (or its left-right mirror image on the board) also occurs in an
   earlier split is removed with all its angles. Precedence: train, then val, then test.
3. Variants without a grade are dropped; nothing post-hoc becomes a feature (``ascents`` is
   label metadata for weighting and subsets only).
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import sqlite3
import sys
from collections import Counter, defaultdict
from pathlib import Path

ML_ROOT = Path(__file__).resolve().parents[2]
REPO_ROOT = ML_ROOT.parent
DEFAULT_DATA_ROOT = REPO_ROOT / "data"
MANIFEST_DIR = ML_ROOT / "datasets" / "manifests"
SPLIT_ORDER = ("train", "val", "test")
ROW_FIELDS = ("route_uuid", "angle_deg", "holds", "grade", "ascents", "hold_set_hash")
FEATURE_FIELDS = ("angle_deg", "holds")
LABEL_FIELDS = ("grade",)
METADATA_FIELDS = ("route_uuid", "ascents", "hold_set_hash")
HASH_CHUNK = 1 << 20
ID_LEN = 12
LICENSE = "LOCAL_RESEARCH_ONLY"


class Refused(Exception):
    """An input or safety check failed."""


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while chunk := handle.read(HASH_CHUNK):
            digest.update(chunk)
    return digest.hexdigest()


def hold_set_hash(pairs: list[tuple[str, str]]) -> str:
    return hashlib.sha256("|".join(sorted(f"{h}:{r}" for h, r in pairs)).encode()).hexdigest()


def mirror_map(holds: dict[str, tuple[int, int]]) -> dict[str, str]:
    """Hold id -> id of the hold at the left-right mirrored raw position, where one exists."""
    xs = [x for x, _ in holds.values()]
    axis_sum = min(xs) + max(xs)
    by_position = {position: hold for hold, position in holds.items()}
    pairs = {h: by_position.get((axis_sum - x, y)) for h, (x, y) in holds.items()}
    return {h: m for h, m in pairs.items() if m is not None}


def mirror_hash(pairs: list[tuple[str, str]], mirror: dict[str, str]) -> str | None:
    if any(h not in mirror for h, _ in pairs):
        return None
    return hold_set_hash([(mirror[h], r) for h, r in pairs])


def read_grade_names(db: Path) -> dict[int, str]:
    con = sqlite3.connect(f"file:{db.resolve()}?mode=ro", uri=True)
    try:
        return {
            int(d): str(n)
            for d, n in con.execute("SELECT difficulty, boulder_name FROM difficulty_grades")
        }
    finally:
        con.close()


def read_catalog(catalog_dir: Path):
    lines = (catalog_dir / "catalog.jsonl").read_text(encoding="utf-8").splitlines()
    manifest, board = json.loads(lines[0]), json.loads(lines[1])
    if manifest.get("license_review_status") != LICENSE or manifest.get("synthetic") is not False:
        raise Refused("catalog manifest is not a LOCAL_RESEARCH_ONLY Kilter extract")
    splits = json.loads((catalog_dir / "splits.json").read_text(encoding="utf-8"))["splits"]
    holds = {
        h["provider_hold_id"]: (h["geometry_meta"]["x_raw"], h["geometry_meta"]["y_raw"])
        for h in board["holds"]
    }
    routes = [json.loads(line) for line in lines[2:]]
    return manifest, holds, splits, routes


def assert_disjoint(splits: dict[str, str], routes: list[dict]) -> None:
    for route in routes:
        uuid = route["provider_route_uuid"]
        if splits.get(uuid) not in SPLIT_ORDER:
            raise Refused(f"route {uuid} has no official split")


def dedupe(routes, splits, mirror, report):
    """Return {uuid: kept?} applying train > val > test precedence on exact and mirror hashes."""
    seen_exact: set[str] = set()  # hold-set hashes of routes kept in earlier splits
    seen_mirror: set[str] = set()  # hashes of their left-right mirror images
    keep: dict[str, bool] = {}
    for split in SPLIT_ORDER:
        added_exact: set[str] = set()
        added_mirror: set[str] = set()
        for route in (r for r in routes if splits[r["provider_route_uuid"]] == split):
            pairs = [(p["provider_hold_id"], p["role"]) for p in route["placements"]]
            exact, mirrored = hold_set_hash(pairs), mirror_hash(pairs, mirror)
            uuid = route["provider_route_uuid"]
            if exact in seen_exact:
                report[f"{split}_removed_exact_duplicate"] += 1
                keep[uuid] = False
            elif exact in seen_mirror or (mirrored is not None and mirrored in seen_exact):
                report[f"{split}_removed_mirror_duplicate"] += 1
                keep[uuid] = False
            else:
                keep[uuid] = True
                added_exact.add(exact)
                if mirrored is not None:
                    added_mirror.add(mirrored)
        seen_exact |= added_exact
        seen_mirror |= added_mirror
    return keep


def build_rows(routes, splits, holds, keep, report):
    rows = defaultdict(list)
    for route in routes:
        uuid = route["provider_route_uuid"]
        if not keep[uuid]:
            continue
        pairs = [(p["provider_hold_id"], p["role"]) for p in route["placements"]]
        route_holds = [{"id": h, "x": holds[h][0], "y": holds[h][1], "role": r} for h, r in pairs]
        for variant in route["variants"]:
            if variant["grade_numeric"] is None:
                report["variants_dropped_ungraded"] += 1
                continue
            rows[splits[uuid]].append(
                {
                    "route_uuid": uuid,
                    "angle_deg": variant["angle_deg"],
                    "holds": route_holds,
                    "grade": variant["grade_numeric"],
                    "ascents": variant["grade_observation_count"],
                    "hold_set_hash": hold_set_hash(pairs),
                }
            )
    for split in rows:
        rows[split].sort(key=lambda r: (r["route_uuid"], r["angle_deg"]))
    return rows


def grade_bin(grade: float) -> int:
    """Nearest integer difficulty, halves rounded up (Python's round() would round to even)."""
    return math.floor(grade + 0.5)


def describe(rows, grade_names) -> dict:
    stats = {}
    for split, items in rows.items():
        bins = Counter(grade_bin(r["grade"]) for r in items)
        stats[split] = {
            "rows": len(items),
            "routes": len({r["route_uuid"] for r in items}),
            "by_angle": {
                str(a): n for a, n in sorted(Counter(r["angle_deg"] for r in items).items())
            },
            "by_grade": [
                {"difficulty": d, "name": grade_names.get(d, f"numeric-{d}"), "rows": bins[d]}
                for d in sorted(bins)
            ],
            "rows_with_ascents_ge_3": sum(1 for r in items if r["ascents"] >= 3),
        }
    return stats


def freeze(
    catalog_dir: Path,
    db: Path,
    data_root: Path = DEFAULT_DATA_ROOT,
    manifest_dir: Path = MANIFEST_DIR,
) -> dict:
    catalog_manifest, holds, splits, routes = read_catalog(Path(catalog_dir))
    assert_disjoint(splits, routes)
    report: Counter = Counter()
    keep = dedupe(routes, splits, mirror_map(holds), report)
    rows = build_rows(routes, splits, holds, keep, report)

    staging = Path(data_root) / "datasets" / "model-a-staging"
    staging.mkdir(parents=True, exist_ok=True)
    file_hashes = {}
    for split in SPLIT_ORDER:
        path = staging / f"{split}.jsonl"
        text = "".join(
            json.dumps(r, sort_keys=True, separators=(",", ":")) + "\n" for r in rows[split]
        )
        path.write_text(text, encoding="utf-8")
        file_hashes[split] = file_sha256(path)
    dataset_id = hashlib.sha256("|".join(file_hashes[s] for s in SPLIT_ORDER).encode()).hexdigest()[
        :ID_LEN
    ]
    out_dir = Path(data_root) / "datasets" / f"model-a-{dataset_id}"
    if out_dir.exists():
        # Same id means byte-identical rows; keep the existing directory so its test-access
        # log (the record of every unsealing) survives a re-freeze.
        for child in staging.iterdir():
            child.unlink()
        staging.rmdir()
    else:
        staging.rename(out_dir)

    manifest = {
        "dataset_id": f"model-a-{dataset_id}",
        "license_review_status": LICENSE,
        "source_catalog_content_sha256": catalog_manifest["content_sha256"],
        "source_catalog_version": catalog_manifest["version"],
        "files_sha256": file_hashes,
        "sealed_split": "test",
        "sealed_sha256": file_hashes["test"],
        "row_fields": list(ROW_FIELDS),
        "feature_fields": list(FEATURE_FIELDS),
        "label_fields": list(LABEL_FIELDS),
        "metadata_fields_not_features": list(METADATA_FIELDS),
        "policy": {
            "split": "official uuid splits, re-asserted disjoint",
            "dedupe": "hold-set hash over sorted hold:role pairs, angle ignored; exact and "
            "left-right mirror matches removed from later splits; precedence train>val>test",
            "ungraded": "dropped",
        },
        "dedupe_counts": dict(sorted(report.items())),
        "splits": describe(rows, read_grade_names(Path(db))),
    }
    (out_dir / "dataset-report.json").write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n"
    )
    Path(manifest_dir).mkdir(parents=True, exist_ok=True)
    manifest_path = Path(manifest_dir) / f"model-a-{dataset_id}.json"
    manifest_path.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
    return {"manifest": str(manifest_path), "out_dir": str(out_dir), **manifest}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Freeze the Model A dataset (local only).")
    parser.add_argument("--catalog-dir", type=Path, required=True)
    parser.add_argument("--db", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        result = freeze(args.catalog_dir, args.db)
    except Refused as exc:
        print(f"REFUSED: {exc}", file=sys.stderr)
        return 2
    print(json.dumps({k: result[k] for k in ("dataset_id", "manifest", "dedupe_counts")}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
