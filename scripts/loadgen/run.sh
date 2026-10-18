#!/usr/bin/env bash
# BC-0311: thin wrapper so the generator always runs from the repo root with an isolated
# interpreter (-I: no site, no PYTHONPATH, script dir off sys.path; stdlib only). Usage:
#   scripts/loadgen/run.sh --users 10 --rate 5 --duration 30 [--out reports/loadgen/<name>]
# Every option is loadgen.py's (see --help and scripts/loadgen/README.md). Never prints tokens.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
exec python3 -I scripts/loadgen/loadgen.py "$@"
