#!/usr/bin/env bash
set -euo pipefail

python3 - <<'PY'
import json
import subprocess
from pathlib import Path

tracked = subprocess.check_output(["git", "ls-files", "-z"]).decode().split("\0")
failed = False
for name in filter(None, tracked):
    path = Path(name)
    if ".sqlite" in path.name.lower():
        print(f"ERROR: tracked SQLite file: {name}")
        failed = True
    if path.suffix == ".jsonl" and path.is_file():
        with path.open(encoding="utf-8") as stream:
            first = stream.readline()
        try:
            manifest = json.loads(first)
        except json.JSONDecodeError:
            manifest = None
        if isinstance(manifest, dict) and manifest.get("synthetic") is False:
            print(f"ERROR: tracked non-synthetic JSONL: {name}")
            failed = True
if failed:
    raise SystemExit(1)
print("Licensed-data guard passed: no tracked SQLite or non-synthetic JSONL manifest")
PY
