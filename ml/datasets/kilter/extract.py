"""Kilter SQLite -> catalog v1 JSON Lines (BC-0103). LOCAL RESEARCH ONLY, never committed.

Reads the Hugging Face ``Vilin97/KilterBoard`` splits file (layout 1, size 10, sets 1 and 20)
and writes, under ``data/catalog/kilter-<input sha12>/`` (gitignored):

* ``catalog.jsonl``  catalog v1 (contracts/catalog/README.md): manifest, one board, one route
  per climb uuid with one variant per angle. ``synthetic: false`` and
  ``license_review_status: LOCAL_RESEARCH_ONLY``, so the importer needs ``--acknowledge-audit``.
* ``splits.json``    uuid -> official split (train/val/test) for ML. Not part of the catalog.
* ``extract-report.json``  counts, rejections by reason, role mapping found, normalisation.

Rights gate (docs/data-source-audit.md section 7): refuses to run unless the operator passes
``--acknowledge-audit``, the input manifest says ``LOCAL_RESEARCH_ONLY``, the sqlite sha256
matches that manifest, and the audit file carries the owner's signed acceptance line.

Personal data: the extractor never SELECTs ``name``, ``description``, ``setter_*``,
``fa_*``, ``beta_*`` or any other column outside ``CLIMB_COLUMNS``; routes are written with
``name: null`` and ``setter_pseudonym: null`` (audit section 0 item 5).

Run from ``ml/`` with an isolated interpreter (``make extract-kilter``)::

    python -I datasets/kilter/extract.py --db ../data/raw/kilter_splits.sqlite \
        --manifest datasets/manifests/kilter-0a0bc5b1bed1.json --acknowledge-audit

Exit codes: 0 OK, 2 refused (gate, input or usage error).
"""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import re
import sqlite3
import sys
from collections import Counter
from dataclasses import dataclass, field
from datetime import UTC, datetime
from pathlib import Path

EXIT_OK = 0
EXIT_REFUSED = 2

ML_ROOT = Path(__file__).resolve().parents[2]
REPO_ROOT = ML_ROOT.parent
DEFAULT_AUDIT = REPO_ROOT / "docs" / "data-source-audit.md"
DEFAULT_DATA_ROOT = REPO_ROOT / "data"
GENERATOR = ML_ROOT / "datasets" / "synthetic" / "generate.py"

SPLITS = {"kilter_train": "train", "kilter_val": "val", "kilter_test": "test"}
LAYOUT_ID = 1
SET_IDS = (1, 20)
SIZE_ID = "10"
BOARD_ID = "kilter-l1-s10"
SOURCE = "kilter"
REQUIRED_LICENSE = "LOCAL_RESEARCH_ONLY"
SIGNED_LINE = re.compile(r"^\[x\] I accept use \(a\) local research import", re.MULTILINE)
HASH_CHUNK = 1 << 20
COORD_DECIMALS = 3
GRADE_DECIMALS = 2

# Only these climb columns are ever read; personal and free-text columns are never selected.
CLIMB_COLUMNS = (
    "uuid",
    "angle",
    "frames",
    "frames_count",
    "difficulty_numeric",
    "ascensionist_count",
    "is_listed",
    "created_at",
)
FRAME_TOKEN = re.compile(r"p(\d+)r(\d+)")
# placement_roles.name -> catalog role. Matched by NAME, not id: Kilter climbs also reference
# role ids of other Aurora products (e.g. 20-31) whose names are the same four words.
ROLE_BY_NAME = {"start": "START", "middle": "HAND", "finish": "FINISH", "foot": "FOOT"}


class Refused(Exception):
    """The rights gate or an input check refused the run."""


@dataclass(frozen=True)
class Hold:
    placement_id: int
    hole_id: int
    set_id: int
    x_raw: int
    y_raw: int


@dataclass
class Report:
    rows_read: Counter = field(default_factory=Counter)
    rejected: Counter = field(default_factory=Counter)
    duplicate_tokens_dropped: int = 0
    role_ids_seen: Counter = field(default_factory=Counter)


def _load_canonical():
    spec = importlib.util.spec_from_file_location("synthetic_generate_jcs", GENERATOR)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.canonical_json, module.content_sha256


canonical_json, content_sha256 = _load_canonical()


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while chunk := handle.read(HASH_CHUNK):
            digest.update(chunk)
    return digest.hexdigest()


# --------------------------------------------------------------------------------------
# Gate
# --------------------------------------------------------------------------------------
def check_gate(db: Path, manifest: dict, audit: Path, acknowledged: bool) -> str:
    """Return the verified input sha256 or raise Refused."""
    if not acknowledged:
        raise Refused("pass --acknowledge-audit after reading docs/data-source-audit.md")
    if manifest.get("license_review_status") != REQUIRED_LICENSE:
        raise Refused(f"manifest license_review_status is not {REQUIRED_LICENSE}")
    if not audit.is_file() or not SIGNED_LINE.search(audit.read_text(encoding="utf-8")):
        raise Refused(f"no signed local-research acceptance line in {audit}")
    if not db.is_file():
        raise Refused(f"sqlite not found: {db}")
    actual = file_sha256(db)
    if actual != manifest.get("sha256"):
        raise Refused(f"sqlite sha256 {actual} does not match the manifest")
    return actual


def check_out_dir(out_dir: Path, data_root: Path) -> None:
    resolved, root = out_dir.resolve(), data_root.resolve()
    if resolved != root and root not in resolved.parents:
        raise Refused(f"--out must be inside {root} (gitignored); got {resolved}")


# --------------------------------------------------------------------------------------
# Reading
# --------------------------------------------------------------------------------------
def read_roles(con: sqlite3.Connection) -> dict[int, str]:
    roles = {}
    for role_id, name in con.execute("SELECT id, name FROM placement_roles"):
        mapped = ROLE_BY_NAME.get(str(name).lower())
        if mapped:
            roles[int(role_id)] = mapped
    return roles


def read_holds(con: sqlite3.Connection) -> dict[int, Hold]:
    marks = ",".join("?" for _ in SET_IDS)
    rows = con.execute(
        "SELECT p.id, p.hole_id, p.set_id, h.x, h.y FROM placements p "
        f"JOIN holes h ON h.id = p.hole_id WHERE p.layout_id = ? AND p.set_id IN ({marks}) "
        "ORDER BY p.id",
        (LAYOUT_ID, *SET_IDS),
    )
    return {int(r[0]): Hold(int(r[0]), int(r[1]), int(r[2]), int(r[3]), int(r[4])) for r in rows}


def parse_frames(frames: str, holds: dict[int, Hold], roles: dict[int, str], report: Report):
    """Return [(placement_id, role)] in frame order, or a rejection reason string."""
    tokens = FRAME_TOKEN.findall(frames)
    if not tokens or "".join(f"p{p}r{r}" for p, r in tokens) != frames:
        return "MALFORMED_FRAMES"
    seen, placements = set(), []
    for placement, role_id in ((int(p), int(r)) for p, r in tokens):
        report.role_ids_seen[role_id] += 1
        if placement not in holds:
            return "UNKNOWN_PLACEMENT"
        if role_id not in roles:
            return "UNKNOWN_ROLE"
        key = (placement, roles[role_id])
        if key in seen:
            report.duplicate_tokens_dropped += 1
            continue
        seen.add(key)
        placements.append(key)
    return placements


def _timestamp(text: str | None) -> str | None:
    """'2019-02-18 22:45:35.747718' -> RFC 3339. The source carries no zone; UTC is assumed."""
    if not text:
        return None
    return datetime.fromisoformat(text).replace(tzinfo=UTC).isoformat().replace("+00:00", "Z")


# --------------------------------------------------------------------------------------
# Building
# --------------------------------------------------------------------------------------
def build_board(holds: dict[int, Hold], angles: list[int]) -> tuple[dict, dict]:
    xs = [h.x_raw for h in holds.values()]
    ys = [h.y_raw for h in holds.values()]
    x0, y0 = min(xs), min(ys)
    span_x, span_y = max(xs) - x0, max(ys) - y0
    board_holds = [
        {
            "provider_hold_id": f"p{h.placement_id}",
            "x": round((h.x_raw - x0) / span_x, COORD_DECIMALS),
            "y": round((h.y_raw - y0) / span_y, COORD_DECIMALS),
            "geometry_meta": {
                "placement_id": h.placement_id,
                "hole_id": h.hole_id,
                "set_id": h.set_id,
                "x_raw": h.x_raw,
                "y_raw": h.y_raw,
            },
        }
        for h in sorted(holds.values(), key=lambda h: h.placement_id)
    ]
    board = {
        "type": "board",
        "board_id": BOARD_ID,
        "provider": SOURCE,
        "layout_id": str(LAYOUT_ID),
        "size_id": SIZE_ID,
        "synthetic": False,
        "geometry": {"units": "NORMALIZED", "width": 1, "height": 1, "y_axis": "UP"},
        "available_angles": angles,
        "holds": board_holds,
    }
    normalisation = {"x_min": x0, "x_span": span_x, "y_min": y0, "y_span": span_y}
    return board, normalisation


def read_climbs(con, holds, roles, report):
    """Yield (split, uuid, created_at, placements, variant) for every accepted row."""
    columns = ", ".join(CLIMB_COLUMNS)
    for table, split in SPLITS.items():
        for row in con.execute(f"SELECT {columns} FROM {table} ORDER BY uuid, angle"):
            uuid, angle, frames, frames_count, grade, ascents, listed, created = row
            report.rows_read[split] += 1
            if frames_count != 1:
                report.rejected["MULTI_FRAME"] += 1
                continue
            if angle is None or angle != int(angle):
                report.rejected["NON_INTEGER_ANGLE"] += 1
                continue
            parsed = parse_frames(frames or "", holds, roles, report)
            if isinstance(parsed, str):
                report.rejected[parsed] += 1
                continue
            variant = {
                "angle_deg": int(angle),
                "grade_numeric": None if grade is None else round(float(grade), GRADE_DECIMALS),
                "grade_observation_count": 0 if grade is None else int(ascents or 0),
                "validation_status": "ACTIVE" if listed else "RETIRED",
            }
            yield split, uuid, created, parsed, variant


def group_routes(climbs, report) -> tuple[list[dict], dict[str, str]]:
    routes: dict[str, dict] = {}
    splits: dict[str, str] = {}
    for split, uuid, created, placements, variant in climbs:
        route = routes.get(uuid)
        if route is None:
            routes[uuid] = {
                "type": "route",
                "provider_route_uuid": uuid,
                "board_id": BOARD_ID,
                "name": None,
                "setter_pseudonym": None,
                "tags": [],
                "active_from": _timestamp(created),
                "active_to": None,
                "placements": [
                    {"provider_hold_id": f"p{p}", "role": role, "placement_order": i}
                    for i, (p, role) in enumerate(placements)
                ],
                "variants": [variant],
            }
            splits[uuid] = split
            continue
        if splits[uuid] != split:
            report.rejected["UUID_IN_TWO_SPLITS"] += 1
        elif any(v["angle_deg"] == variant["angle_deg"] for v in route["variants"]):
            report.rejected["DUPLICATE_ANGLE"] += 1
        else:
            route["variants"].append(variant)
    ordered = [routes[k] for k in sorted(routes)]
    for route in ordered:
        route["variants"].sort(key=lambda v: v["angle_deg"])
    return ordered, splits


# --------------------------------------------------------------------------------------
# Main
# --------------------------------------------------------------------------------------
def extract(
    db,
    manifest_path,
    out_dir,
    acknowledged,
    audit=DEFAULT_AUDIT,
    data_root=DEFAULT_DATA_ROOT,
    now=None,
) -> dict:
    manifest = json.loads(Path(manifest_path).read_text(encoding="utf-8"))
    input_sha = check_gate(Path(db), manifest, Path(audit), acknowledged)
    out_dir = Path(out_dir) if out_dir else Path(data_root) / "catalog" / f"kilter-{input_sha[:12]}"
    check_out_dir(out_dir, Path(data_root))

    report = Report()
    con = sqlite3.connect(f"file:{Path(db).resolve()}?mode=ro", uri=True)
    try:
        roles = read_roles(con)
        holds = read_holds(con)
        routes, splits = group_routes(read_climbs(con, holds, roles, report), report)
    finally:
        con.close()

    angles = sorted({v["angle_deg"] for r in routes for v in r["variants"]})
    board, normalisation = build_board(holds, angles)
    body = [canonical_json(board)] + [canonical_json(r) for r in routes]
    extracted_at = (now or datetime.now(UTC)).strftime("%Y-%m-%dT%H:%M:%SZ")
    catalog_manifest = {
        "type": "manifest",
        "schema_version": 1,
        "source": SOURCE,
        "version": f"kilter-l1-s10-{input_sha[:12]}",
        "content_sha256": content_sha256(body),
        "extracted_at": extracted_at,
        "synthetic": False,
        "license_review_status": REQUIRED_LICENSE,
        "record_counts": {"boards": 1, "routes": len(routes)},
        "notes": "LOCAL RESEARCH ONLY. Derived from Vilin97/KilterBoard; never commit, publish "
        "or redistribute (docs/data-source-audit.md section 7).",
    }
    out_dir.mkdir(parents=True, exist_ok=True)
    catalog_path = out_dir / "catalog.jsonl"
    catalog_path.write_bytes(("\n".join([canonical_json(catalog_manifest)] + body) + "\n").encode())
    (out_dir / "splits.json").write_text(
        json.dumps({"input_sha256": input_sha, "splits": splits}, sort_keys=True) + "\n"
    )
    summary = {
        "input_sha256": input_sha,
        "input_manifest": str(manifest_path),
        "catalog": str(catalog_path),
        "catalog_file_sha256": file_sha256(catalog_path),
        "content_sha256": catalog_manifest["content_sha256"],
        "extracted_at": extracted_at,
        "routes": len(routes),
        "variants": sum(len(r["variants"]) for r in routes),
        "board_holds": len(board["holds"]),
        "available_angles": angles,
        "routes_per_split": dict(sorted(Counter(splits.values()).items())),
        "rows_read": dict(sorted(report.rows_read.items())),
        "rows_rejected": dict(sorted(report.rejected.items())),
        "duplicate_tokens_dropped": report.duplicate_tokens_dropped,
        "role_ids_seen": {str(k): v for k, v in sorted(report.role_ids_seen.items())},
        "role_mapping": {str(k): v for k, v in sorted(roles.items())},
        "normalisation": normalisation,
    }
    (out_dir / "extract-report.json").write_text(
        json.dumps(summary, indent=2, sort_keys=True) + "\n"
    )
    return summary


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Kilter splits sqlite -> catalog v1 (local only).")
    parser.add_argument("--db", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--out", type=Path, default=None)
    parser.add_argument("--acknowledge-audit", action="store_true")
    args = parser.parse_args(argv)
    try:
        summary = extract(args.db, args.manifest, args.out, args.acknowledge_audit)
    except Refused as exc:
        print(f"REFUSED: {exc}", file=sys.stderr)
        return EXIT_REFUSED
    print(json.dumps({k: summary[k] for k in ("catalog", "routes", "variants", "rows_rejected")}))
    return EXIT_OK


if __name__ == "__main__":
    sys.exit(main())
