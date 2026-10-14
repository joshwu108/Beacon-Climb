#!/usr/bin/env bash
set -euo pipefail

python3 - <<'PY'
import fnmatch
import json
import os
import re
import subprocess
from pathlib import Path

branch = os.environ.get("GITHUB_HEAD_REF") or subprocess.check_output(
    ["git", "branch", "--show-current"], text=True
).strip()
match = re.search(r"BC-\d{4}", branch)
if not match:
    print(f"NOTICE: no BC ticket in branch {branch!r}; ownership check skipped")
    raise SystemExit(0)
ticket = match.group()
role = json.loads(Path("scripts/ci/owners.json").read_text()).get(ticket)
if not role:
    print(f"NOTICE: no owner role mapped for {ticket}; ownership check skipped")
    raise SystemExit(0)
base = os.environ.get("GITHUB_BASE_REF") or "integration"
if subprocess.run(["git", "show-ref", "--verify", "--quiet", f"refs/heads/{base}"]).returncode:
    print(f"NOTICE: base branch {base} unavailable; ownership check skipped")
    raise SystemExit(0)
changed = subprocess.check_output(["git", "diff", "--name-only", f"{base}...HEAD"], text=True).splitlines()
patterns = []
for line in Path("CODEOWNERS").read_text().splitlines():
    parts = line.split()
    if parts and not parts[0].startswith("#") and len(parts) > 1:
        patterns.append((parts[0].lstrip("/"), parts[1]))
warnings = 0
for path in changed:
    owner = None
    for pattern, candidate in patterns:
        if pattern.endswith("/") and path.startswith(pattern):
            owner = candidate
        elif fnmatch.fnmatch(path, pattern):
            owner = candidate
    if owner and owner != role:
        print(f"WARNING: {ticket} ({role}) changed {path} owned by {owner}")
        warnings += 1
print(f"Ownership check: {len(changed)} changed paths, {warnings} warning(s); warn-only in Sprint 0")
PY
