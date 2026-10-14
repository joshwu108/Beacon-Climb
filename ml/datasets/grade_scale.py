"""Reference implementation of the BC-0014 grade ambiguity policy (stdlib only).

Source of truth: contracts/catalog/grade-scale.v1.json ("ambiguity_policy").
This module performs no I/O beyond reading that JSON and contains no grade data.
"""

from __future__ import annotations

import json
import math
from pathlib import Path

HALF_STEP = 0.5
ENTRY_KEYS = frozenset({"difficulty_numeric", "v_grade", "font_grade"})
DEFAULT_PATH = Path(__file__).resolve().parents[2] / "contracts" / "catalog" / "grade-scale.v1.json"


def load_scale(path: Path = DEFAULT_PATH) -> dict:
    return json.loads(Path(path).read_text(encoding="utf-8"))


def validate_entries(entries: list[dict]) -> None:
    """Raise TypeError/ValueError unless entries match entry_schema and strictly increase."""
    previous = None
    for index, entry in enumerate(entries):
        if set(entry) != ENTRY_KEYS:
            raise ValueError(f"entry {index}: keys {sorted(entry)} != {sorted(ENTRY_KEYS)}")
        value = entry["difficulty_numeric"]
        if isinstance(value, bool) or not isinstance(value, int):
            raise TypeError(f"entry {index}: difficulty_numeric must be int")
        for key in ("v_grade", "font_grade"):
            if not isinstance(entry[key], str) or not entry[key]:
                raise TypeError(f"entry {index}: {key} must be a non-empty string")
        if previous is not None and value <= previous:
            raise ValueError(f"entry {index}: difficulty_numeric not strictly increasing")
        previous = value


def _is_gradeable(value: object) -> bool:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return False
    return not math.isnan(value)


def select_entry(value: object, entries: list[dict]) -> dict | None:
    """Return the entry for a raw difficulty value, or None for 'ungraded'."""
    if not entries or not _is_gradeable(value):
        return None
    low = entries[0]["difficulty_numeric"] - HALF_STEP
    high = entries[-1]["difficulty_numeric"] + HALF_STEP
    if value < low or value > high:
        return None
    # min() keeps the first minimum, so with ascending entries an exact tie picks the lower one.
    return min(entries, key=lambda entry: abs(value - entry["difficulty_numeric"]))


def grade_label(value: object, entries: list[dict], system: str, ungraded: str) -> str:
    """Display label in 'v_grade' or 'font_grade' for a raw value."""
    entry = select_entry(value, entries)
    return ungraded if entry is None else entry[system]
