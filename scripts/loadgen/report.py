"""BC-0311: freshness/commit-latency summary over a loadgen events.jsonl (stdlib only).

Shared by loadgen.py (writes the summary at the end of a run) and freshness.py (recomputes it
from a stored events.jsonl). Every number here is computed from the rows it is given; nothing
is estimated. Percentiles use the nearest-rank method: the p-th percentile of n sorted values
is the value at rank ceil(p/100 * n) (1-based), so p100 is the maximum and every reported
percentile is an observed value, never an interpolation.
"""

from __future__ import annotations

import json
import math
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

PERCENTILE_METHOD = "nearest-rank: value at 1-based rank ceil(p/100*n) of the sorted sample"
DISCLAIMER = "SYNTHETIC load; numbers describe this laptop only; never a product claim"
PERCENTILES = (50, 95, 99)

VISIBLE = "VISIBLE"
NOT_VISIBLE = "NOT_VISIBLE"
NOT_COMMITTED = "NOT_COMMITTED"


def percentile(sorted_values: list[float], p: float) -> float:
    """Nearest-rank percentile of an ascending list; the list must be non-empty."""
    if not sorted_values:
        raise ValueError("percentile of an empty sample")
    if not 0 < p <= 100:
        raise ValueError(f"percentile p must be in (0, 100], got {p}")
    rank = max(1, math.ceil(p / 100 * len(sorted_values)))
    return sorted_values[rank - 1]


def distribution(values: list[float]) -> dict[str, Any]:
    """n, min, p50/p95/p99, max and mean of a sample (all None when the sample is empty)."""
    if not values:
        return {"n": 0, "min": None, "p50": None, "p95": None, "p99": None, "max": None, "mean": None}
    ordered = sorted(values)
    out: dict[str, Any] = {"n": len(ordered), "min": ordered[0]}
    for p in PERCENTILES:
        out[f"p{p}"] = percentile(ordered, p)
    out["max"] = ordered[-1]
    out["mean"] = round(sum(ordered) / len(ordered), 3)
    return out


def parse_rfc3339(value: str) -> datetime:
    """RFC 3339 with any number of fractional digits (the api prints up to nanoseconds)."""
    text = value.strip()
    if text.endswith("Z") or text.endswith("z"):
        text = text[:-1] + "+00:00"
    if "." in text:
        head, rest = text.split(".", 1)
        digits = ""
        i = 0
        while i < len(rest) and rest[i].isdigit():
            digits += rest[i]
            i += 1
        text = f"{head}.{(digits + '000000')[:6]}{rest[i:]}"
    parsed = datetime.fromisoformat(text)
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc)


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def millis_between(start: str, end: str) -> float:
    return round((parse_rfc3339(end) - parse_rfc3339(start)).total_seconds() * 1000.0, 3)


def load_events(path: Path) -> list[dict[str, Any]]:
    """Reads events.jsonl; refuses a file with a row that is not marked synthetic."""
    events: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as handle:
        for line_no, line in enumerate(handle, start=1):
            line = line.strip()
            if not line:
                continue
            row = json.loads(line)
            if row.get("synthetic") is not True:
                raise ValueError(f"{path}:{line_no}: row is not marked synthetic; refusing")
            events.append(row)
    return events


def summarise(events: list[dict[str, Any]], meta: dict[str, Any]) -> dict[str, Any]:
    """Builds summary.json from the per-event rows plus run metadata (parameters, hardware)."""
    committed = [e for e in events if e.get("status") in (200, 201)]
    replays = [e for e in committed if e.get("status") == 200]
    failed = [e for e in events if e.get("status") not in (200, 201)]
    visible = [e for e in committed if e.get("visibility") == VISIBLE]
    not_visible = [e for e in committed if e.get("visibility") != VISIBLE]

    freshness = [float(e["freshness_ms"]) for e in visible if e.get("freshness_ms") is not None]
    commit_latency = [float(e["commit_latency_ms"]) for e in committed if e.get("commit_latency_ms") is not None]
    poll_latency = [float(e["visible_poll_latency_ms"]) for e in visible if e.get("visible_poll_latency_ms") is not None]
    clock_offsets = [float(e["server_clock_offset_ms"]) for e in committed if e.get("server_clock_offset_ms") is not None]

    sent_at = [e["sent_at"] for e in events if e.get("sent_at")]
    committed_at = [e["committed_at"] for e in committed if e.get("committed_at")]
    window_s = None
    achieved_rate = None
    if sent_at and committed_at:
        window_s = round((parse_rfc3339(max(committed_at)) - parse_rfc3339(min(sent_at))).total_seconds(), 3)
        achieved_rate = round(len(committed) / window_s, 3) if window_s and window_s > 0 else None

    failed_by_status = Counter(str(e.get("status")) for e in failed)
    rate_limited_responses = sum(int(e.get("rate_limited_responses", 0)) for e in events)

    return {
        "synthetic": True,
        "disclaimer": DISCLAIMER,
        "run_id": meta.get("run_id"),
        "generated_at": meta.get("generated_at"),
        "parameters": meta.get("parameters", {}),
        "hardware": meta.get("hardware", {}),
        "api_environment": meta.get("api_environment", {}),
        "percentile_method": PERCENTILE_METHOD,
        "counts": {
            "attempted": len(events),
            "committed_201": len(committed),
            "idempotent_replays_200": len(replays),
            "failed": len(failed),
            "failed_by_status": dict(sorted(failed_by_status.items())),
            "rate_limited_responses_429_total": rate_limited_responses,
            "poll_rate_limited_responses_429_total": int(meta.get("poll_rate_limited_responses", 0)),
            "visible": len(visible),
            "not_visible": len(not_visible),
            "users": len({e.get("username") for e in events}),
        },
        "rate": {
            "target_events_per_s": meta.get("parameters", {}).get("rate"),
            "achieved_committed_events_per_s": achieved_rate,
            "window_s_first_send_to_last_commit": window_s,
        },
        "freshness_ms_visible_only": distribution(freshness),
        "commit_latency_ms": distribution(commit_latency),
        "visible_poll_latency_ms": distribution(poll_latency),
        "server_clock_offset_ms_diagnostic": distribution(clock_offsets),
        "features_source_first_visible": dict(sorted(Counter(e.get("features_source") for e in visible).items())),
        "interrupted": bool(meta.get("interrupted", False)),
        "regenerated_from": meta.get("regenerated_from"),
    }


def _fmt(value: Any) -> str:
    if value is None:
        return "n/a"
    if isinstance(value, float):
        return f"{value:.1f}"
    return str(value)


def _dist_line(label: str, dist: dict[str, Any]) -> str:
    return (
        f"{label}: n={dist['n']} p50={_fmt(dist['p50'])} p95={_fmt(dist['p95'])} "
        f"p99={_fmt(dist['p99'])} max={_fmt(dist['max'])} min={_fmt(dist['min'])} mean={_fmt(dist['mean'])}"
    )


def render_text(summary: dict[str, Any]) -> str:
    counts = summary["counts"]
    rate = summary["rate"]
    params = summary["parameters"]
    lines = [
        "BC-0311 synthetic load run (loadgen.py)",
        f"run_id: {summary['run_id']}",
        f"generated_at: {summary['generated_at']}",
        f"interrupted: {summary['interrupted']}",
        "",
        "parameters:",
    ]
    lines += [f"  {k}: {v}" for k, v in params.items()]
    lines += ["", "hardware:"]
    lines += [f"  {k}: {v}" for k, v in summary["hardware"].items()]
    lines += ["", "api environment (docker inspect, allow-listed BEACON_* names only; blank = container not inspected):"]
    env = summary["api_environment"]
    lines += [f"  {k}: {v}" for k, v in env.items()] if env else ["  (none)"]
    lines += [
        "",
        "counts:",
        f"  attempted: {counts['attempted']}",
        f"  committed (201, plus {counts['idempotent_replays_200']} idempotent 200 replays of a retried key): {counts['committed_201']}",
        f"  failed: {counts['failed']} by status: {counts['failed_by_status']}",
        f"  429 responses on attempts (retried with Retry-After): {counts['rate_limited_responses_429_total']}",
        f"  429 responses on recommendation polls: {counts['poll_rate_limited_responses_429_total']}",
        f"  visible: {counts['visible']}",
        f"  not visible (timed out before feature_as_of >= occurred_at): {counts['not_visible']}",
        f"  users: {counts['users']}",
        "",
        "rate:",
        f"  target: {_fmt(rate['target_events_per_s'])} events/s",
        f"  achieved (committed 201 / window first send -> last commit): {_fmt(rate['achieved_committed_events_per_s'])} events/s over {_fmt(rate['window_s_first_send_to_last_commit'])} s",
        "",
        _dist_line("freshness ms = visible_at - occurred_at (VISIBLE events only; NOT_VISIBLE excluded)", summary["freshness_ms_visible_only"]),
        _dist_line("commit latency ms = committed_at - sent_at (201 responses; includes any Retry-After backoff)", summary["commit_latency_ms"]),
        _dist_line("recommendation poll latency ms (the poll that first showed each event)", summary["visible_poll_latency_ms"]),
        _dist_line("server clock offset ms = api received_at (at receipt) - client committed_at (after response); about -(server processing time) when clocks agree; diagnostic", summary["server_clock_offset_ms_diagnostic"]),
        f"features_source of first visible response: {summary['features_source_first_visible']}",
        f"percentiles: {summary['percentile_method']}",
        "",
        summary["disclaimer"],
        "",
    ]
    return "\n".join(lines)


def write_outputs(summary: dict[str, Any], out_dir: Path, evidence_copy: Path | None) -> None:
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "summary.json").write_text(json.dumps(summary, indent=2, sort_keys=False) + "\n", encoding="utf-8")
    text = render_text(summary)
    (out_dir / "summary.txt").write_text(text, encoding="utf-8")
    if evidence_copy is not None:
        evidence_copy.parent.mkdir(parents=True, exist_ok=True)
        evidence_copy.write_text(text, encoding="utf-8")


def self_test() -> None:
    """Fixed fixture through percentile() and summarise(); raises AssertionError on drift."""
    sample = [float(v) for v in (10, 20, 30, 40, 50, 60, 70, 80, 90, 100)]
    assert percentile(sample, 50) == 50.0, percentile(sample, 50)
    assert percentile(sample, 95) == 100.0
    assert percentile(sample, 99) == 100.0
    assert percentile(sample, 100) == 100.0
    assert percentile(sample, 1) == 10.0
    assert percentile([7.0], 50) == 7.0
    assert percentile(sorted([5.0, 1.0, 3.0]), 50) == 3.0  # rank ceil(1.5)=2
    assert parse_rfc3339("2026-10-18T10:00:00.123456789Z").microsecond == 123456
    assert millis_between("2026-10-18T10:00:00.000Z", "2026-10-18T10:00:01.250Z") == 1250.0

    def row(i: int, status: int, visibility: str, freshness: float | None, source: str | None) -> dict[str, Any]:
        return {
            "synthetic": True,
            "username": f"synthetic_selftest_{i % 2}",
            "status": status,
            "sent_at": "2026-10-18T10:00:00.000Z",
            "committed_at": "2026-10-18T10:00:10.000Z" if status == 201 else None,
            "commit_latency_ms": 12.0 + i if status == 201 else None,
            "visibility": visibility,
            "freshness_ms": freshness,
            "features_source": source,
            "rate_limited_responses": 1 if status == 429 else 0,
            "server_clock_offset_ms": -1.0 if status == 201 else None,
        }

    events = [
        row(0, 201, VISIBLE, 100.0, "SYNC"),
        row(1, 201, VISIBLE, 300.0, "SYNC"),
        row(2, 201, VISIBLE, 200.0, "STREAM"),
        row(3, 201, NOT_VISIBLE, None, None),
        row(4, 429, NOT_COMMITTED, None, None),
    ]
    summary = summarise(events, {"run_id": "selftest", "parameters": {"rate": 5}})
    counts = summary["counts"]
    assert counts == {
        "attempted": 5,
        "committed_201": 4,
        "failed": 1,
        "failed_by_status": {"429": 1},
        "idempotent_replays_200": 0,
        "rate_limited_responses_429_total": 1,
        "poll_rate_limited_responses_429_total": 0,
        "visible": 3,
        "not_visible": 1,
        "users": 2,
    }, counts
    fresh = summary["freshness_ms_visible_only"]
    assert (fresh["n"], fresh["p50"], fresh["p95"], fresh["p99"], fresh["max"]) == (3, 200.0, 300.0, 300.0, 300.0), fresh
    assert summary["commit_latency_ms"]["n"] == 4 and summary["commit_latency_ms"]["p50"] == 13.0
    assert summary["rate"]["achieved_committed_events_per_s"] == 0.4  # 4 committed over 10 s
    assert summary["features_source_first_visible"] == {"STREAM": 1, "SYNC": 2}
    text = render_text(summary)
    assert DISCLAIMER in text and "nearest-rank" in text
    assert summary["synthetic"] is True
