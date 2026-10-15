#!/usr/bin/env bash
# Repository guard: no licensed or raw climbing data may be tracked (plan D1, TM-12).
# Usage: scripts/ci/check-no-licensed-data.sh            # checks `git ls-files` in the cwd repo
#        scripts/ci/check-no-licensed-data.sh <path>...  # checks the given paths (used by its test)
set -euo pipefail

python3 -I - "$@" <<'PY'
import json
import subprocess
import sys
from pathlib import Path

# Extensions that are never acceptable in version control: raw databases, tabular exports,
# serialized model weights and columnar dumps.
FORBIDDEN_SUFFIXES = {
    ".db", ".csv", ".tsv", ".pt", ".pth", ".parquet", ".sqlite", ".sqlite3",
    ".safetensors", ".onnx", ".pkl", ".pickle", ".npz", ".npy", ".h5", ".hdf5",
    ".feather", ".arrow", ".zip", ".gz", ".bz2", ".xz", ".zst", ".7z", ".tar",
    ".bin", ".ckpt", ".joblib", ".gguf",
}
# Any tracked file starting with the SQLite header is a database whatever its name.
SQLITE_MAGIC = b"SQLite format 3\x00"
GZIP_MAGIC = b"\x1f\x8b"
# JSON Lines catalogs are allowed only when the first line is a manifest declaring
# "synthetic": true (contracts/catalog). Anything else is treated as licensed.
MANIFEST_SUFFIXES = {".jsonl", ".ndjson"}


def tracked_paths(argv):
    if len(argv) > 1:
        return [p for p in argv[1:] if p]
    out = subprocess.check_output(["git", "ls-files", "-z"]).decode()
    return [p for p in out.split("\0") if p]


def binary_header(path: Path):
    try:
        with path.open("rb") as stream:
            head = stream.read(len(SQLITE_MAGIC))
    except OSError:
        return None
    if head == SQLITE_MAGIC:
        return "SQLite file header detected"
    if head[: len(GZIP_MAGIC)] == GZIP_MAGIC:
        return "gzip header detected (compressed data under another name)"
    return None


def manifest_error(path: Path):
    try:
        with path.open(encoding="utf-8") as stream:
            first = stream.readline()
    except (OSError, UnicodeDecodeError) as exc:
        return f"unreadable first line ({exc.__class__.__name__})"
    try:
        manifest = json.loads(first)
    except json.JSONDecodeError:
        return "first line is not a JSON manifest"
    if not isinstance(manifest, dict):
        return "first line is not a JSON object manifest"
    if manifest.get("synthetic") is not True:
        return 'manifest lacks "synthetic": true'
    return None


errors = []
for name in tracked_paths(sys.argv):
    path = Path(name)
    lowered = path.name.lower()
    if ".sqlite" in lowered:
        errors.append(f"{name}: tracked SQLite file")
        continue
    header_problem = binary_header(path) if path.is_file() else None
    if header_problem:
        errors.append(f"{name}: {header_problem}")
        continue
    suffix = path.suffix.lower()
    if suffix in FORBIDDEN_SUFFIXES:
        errors.append(f"{name}: forbidden data/model extension {suffix}")
        continue
    if suffix in MANIFEST_SUFFIXES:
        if not path.is_file():
            errors.append(f"{name}: tracked but missing on disk")
            continue
        problem = manifest_error(path)
        if problem:
            errors.append(f"{name}: {problem}")

for error in errors:
    print(f"ERROR: {error}")
if errors:
    print(f"Licensed-data guard FAILED: {len(errors)} offending path(s)")
    raise SystemExit(1)
print("Licensed-data guard passed: no tracked databases, archives, tabular/model dumps or non-synthetic JSONL")
PY
