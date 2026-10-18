#!/usr/bin/env python3
"""BC-0311: recompute summary.json / summary.txt from a loadgen events.jsonl (stdlib only).

Usage: python3 -I scripts/loadgen/freshness.py <events.jsonl> [--out <dir>] [--evidence-copy <path>]

The run metadata (run id, parameters, hardware, api environment) is taken from the summary.json
next to the events file when one exists, so a regenerated report keeps the environment of the
run that produced the rows; the numbers themselves are always recomputed from the rows.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import report  # noqa: E402


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("events", help="path to events.jsonl written by loadgen.py")
    parser.add_argument("--out", default=None, help="directory for summary.json/summary.txt (default: next to the events file)")
    parser.add_argument("--evidence-copy", default=None, help="also write summary.txt to this path")
    args = parser.parse_args(argv)

    events_path = Path(args.events)
    out_dir = Path(args.out) if args.out else events_path.parent
    rows = report.load_events(events_path)
    meta: dict = {}
    previous = events_path.parent / "summary.json"
    if previous.exists():
        stored = json.loads(previous.read_text(encoding="utf-8"))
        meta = {
            "run_id": stored.get("run_id"),
            "parameters": stored.get("parameters", {}),
            "hardware": stored.get("hardware", {}),
            "api_environment": stored.get("api_environment", {}),
            "poll_rate_limited_responses": stored.get("counts", {}).get("poll_rate_limited_responses_429_total", 0),
            "interrupted": stored.get("interrupted", False),
        }
    meta["generated_at"] = report.now_iso()
    meta["regenerated_from"] = str(events_path)
    summary = report.summarise(rows, meta)
    summary["regenerated_from"] = str(events_path)
    report.write_outputs(summary, out_dir, Path(args.evidence_copy) if args.evidence_copy else None)
    print(report.render_text(summary))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
