"""Verify (or write) a content-hashed manifest for the Kilter splits sqlite.

BC-0002 / Gate D0. Standard library only. Run with an isolated interpreter:

    python3 -I ml/datasets/verify_manifest.py data/raw/kilter_splits.sqlite \
        ml/datasets/manifests/kilter-<sha12>.json

    python3 -I ml/datasets/verify_manifest.py data/raw/kilter_splits.sqlite \
        --write ml/datasets/manifests --template ml/datasets/manifests/kilter-PENDING.json

The sqlite is opened read-only. Only aggregate counts and hashes are emitted;
no route rows, frames strings or names are printed or written.

Exit codes: 0 = OK, 1 = mismatch or split overlap, 2 = usage/input error.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sqlite3
import sys
from datetime import UTC, datetime
from itertools import combinations
from pathlib import Path

EXIT_OK = 0
EXIT_MISMATCH = 1
EXIT_INPUT_ERROR = 2

SPLIT_TABLES = ("kilter_train", "kilter_val", "kilter_test")
HASH_CHUNK_BYTES = 1 << 20
SHA_PREFIX_LEN = 12
DEFAULT_SOURCE_URL = "https://huggingface.co/datasets/Vilin97/KilterBoard"
DEFAULT_FILE = "kilter_splits.sqlite"
LICENSE_REVIEW_STATUS = "LOCAL_RESEARCH_ONLY"
COMPARED_KEYS = ("sha256", "bytes", "tables", "uuid_counts")


class InputError(Exception):
    """Raised for missing files or unusable inputs (exit code 2)."""


def sha256_and_size(path: Path) -> tuple[str, int]:
    digest = hashlib.sha256()
    size = 0
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(HASH_CHUNK_BYTES), b""):
            digest.update(chunk)
            size += len(chunk)
    return digest.hexdigest(), size


def _quote_ident(name: str) -> str:
    return '"' + name.replace('"', '""') + '"'


def open_readonly(path: Path) -> sqlite3.Connection:
    uri = path.resolve().as_uri() + "?mode=ro"
    try:
        conn = sqlite3.connect(uri, uri=True)
        conn.execute("SELECT name FROM sqlite_master LIMIT 1").fetchall()
    except sqlite3.DatabaseError as exc:
        raise InputError(f"not a readable sqlite database: {path} ({exc})") from exc
    return conn


def table_row_counts(conn: sqlite3.Connection) -> dict[str, int]:
    names = [
        row[0]
        for row in conn.execute(
            "SELECT name FROM sqlite_master WHERE type='table' "
            "AND name NOT LIKE 'sqlite_%' ORDER BY name"
        )
    ]
    return {
        name: conn.execute(f"SELECT COUNT(*) FROM {_quote_ident(name)}").fetchone()[0]
        for name in names
    }


def split_uuid_counts(conn: sqlite3.Connection, tables: dict[str, int]) -> dict[str, int]:
    missing = [t for t in SPLIT_TABLES if t not in tables]
    if missing:
        raise InputError(f"split tables missing from sqlite: {', '.join(missing)}")
    return {
        t: conn.execute(f"SELECT COUNT(DISTINCT uuid) FROM {_quote_ident(t)}").fetchone()[0]
        for t in SPLIT_TABLES
    }


def split_overlaps(conn: sqlite3.Connection) -> dict[str, int]:
    """Pairwise count of uuids shared between split tables (must all be 0)."""
    overlaps = {}
    for left, right in combinations(SPLIT_TABLES, 2):
        query = (
            f"SELECT COUNT(*) FROM (SELECT uuid FROM {_quote_ident(left)} "
            f"INTERSECT SELECT uuid FROM {_quote_ident(right)})"
        )
        overlaps[f"{left}&{right}"] = conn.execute(query).fetchone()[0]
    return overlaps


def measure(sqlite_path: Path) -> dict:
    if not sqlite_path.is_file():
        raise InputError(f"sqlite not found: {sqlite_path}")
    sha256, size = sha256_and_size(sqlite_path)
    conn = open_readonly(sqlite_path)
    try:
        tables = table_row_counts(conn)
        uuid_counts = split_uuid_counts(conn, tables)
        overlaps = split_overlaps(conn)
    finally:
        conn.close()
    return {
        "sha256": sha256,
        "bytes": size,
        "tables": tables,
        "uuid_counts": uuid_counts,
        "split_uuid_overlap": overlaps,
    }


def load_json(path: Path) -> dict:
    if not path.is_file():
        raise InputError(f"manifest not found: {path}")
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise InputError(f"manifest is not valid JSON: {path} ({exc})") from exc


def upstream_problems(measured: dict, manifest: dict) -> list[str]:
    upstream = manifest.get("upstream_reported") or {}
    problems = []
    expected_sha = upstream.get("lfs_sha256")
    if expected_sha and expected_sha != measured["sha256"]:
        problems.append(
            f"upstream_reported.lfs_sha256: expected {expected_sha} got {measured['sha256']}"
        )
    expected_size = upstream.get("size_bytes")
    if expected_size is not None and expected_size != measured["bytes"]:
        problems.append(
            f"upstream_reported.size_bytes: expected {expected_size} got {measured['bytes']}"
        )
    return problems


def overlap_problems(measured: dict) -> list[str]:
    return [
        f"split_uuid_overlap {pair}: {count} shared uuid(s), expected 0"
        for pair, count in measured["split_uuid_overlap"].items()
        if count != 0
    ]


def compare(measured: dict, manifest: dict) -> list[str]:
    """Return human-readable problems; empty list means OK."""
    problems = overlap_problems(measured) + upstream_problems(measured, manifest)
    for key in COMPARED_KEYS:
        expected = manifest.get(key)
        if expected is None:
            problems.append(f"{key}: manifest value is null (status={manifest.get('status')})")
        elif expected != measured[key]:
            problems.append(f"{key}: expected {expected!r} got {measured[key]!r}")
    return problems


def build_manifest(measured: dict, template: dict, file_name: str) -> dict:
    return {
        "source_url": template.get("source_url", DEFAULT_SOURCE_URL),
        "hf_revision": template.get("hf_revision"),
        "file": file_name,
        "sha256": measured["sha256"],
        "bytes": measured["bytes"],
        "tables": measured["tables"],
        "uuid_counts": measured["uuid_counts"],
        "split_uuid_overlap": measured["split_uuid_overlap"],
        "upstream_reported": template.get("upstream_reported"),
        "extracted_at": datetime.now(UTC).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "license_review_status": LICENSE_REVIEW_STATUS,
        "status": "MEASURED",
    }


def write_manifest(
    measured: dict, out_dir: Path, template_path: Path | None, file_name: str
) -> int:
    template = load_json(template_path) if template_path else {}
    problems = overlap_problems(measured) + upstream_problems(measured, template)
    if problems:
        print("REFUSING TO WRITE MANIFEST", file=sys.stderr)
        for problem in problems:
            print(f"  - {problem}", file=sys.stderr)
        return EXIT_MISMATCH
    if not out_dir.is_dir():
        raise InputError(f"--write target is not a directory: {out_dir}")
    manifest = build_manifest(measured, template, file_name)
    out_path = out_dir / f"kilter-{measured['sha256'][:SHA_PREFIX_LEN]}.json"
    out_path.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"WROTE {out_path}")
    return EXIT_OK


def parse_args(argv: list[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Verify or write the content-hashed Kilter sqlite manifest (BC-0002).",
        epilog="Exit codes: 0 OK, 1 mismatch/split overlap, 2 missing or unusable input.",
    )
    parser.add_argument("sqlite", type=Path, help="path to kilter_splits.sqlite (read-only)")
    parser.add_argument("manifest", type=Path, nargs="?", help="manifest JSON to verify against")
    parser.add_argument(
        "--write",
        type=Path,
        metavar="DIR",
        help="write DIR/kilter-<sha256[:12]>.json instead of verifying",
    )
    parser.add_argument(
        "--template",
        type=Path,
        help="manifest to copy source_url/hf_revision/upstream_reported from (with --write)",
    )
    args = parser.parse_args(argv)
    if (args.manifest is None) == (args.write is None):
        parser.error("give exactly one of MANIFEST or --write DIR")
    return args


def run(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        measured = measure(args.sqlite)
        if args.write is not None:
            return write_manifest(measured, args.write, args.template, args.sqlite.name)
        problems = compare(measured, load_json(args.manifest))
    except InputError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return EXIT_INPUT_ERROR
    if problems:
        print("MISMATCH")
        for problem in problems:
            print(f"  - {problem}")
        return EXIT_MISMATCH
    print("OK")
    return EXIT_OK


if __name__ == "__main__":
    sys.exit(run())
