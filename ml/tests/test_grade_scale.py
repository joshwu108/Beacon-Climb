"""Tests for contracts/catalog/grade-scale.v1.json and its ambiguity policy (BC-0014).

Policy tests use SYNTHETIC entries (labels 'SYN-V*' / 'SYN-F*'); they are not Kilter grades.
"""

from __future__ import annotations

import importlib.util
import math
import re
import sys
from pathlib import Path

import pytest

MODULE_PATH = Path(__file__).resolve().parents[1] / "datasets" / "grade_scale.py"


def _load_module():
    spec = importlib.util.spec_from_file_location("grade_scale", MODULE_PATH)
    module = importlib.util.module_from_spec(spec)
    sys.modules["grade_scale"] = module
    spec.loader.exec_module(module)
    return module


gs = _load_module()
SCALE = gs.load_scale()
REAL_ENTRIES = SCALE["entries"]
UNGRADED = SCALE["ungraded_label"]
SYNTH = [
    {"difficulty_numeric": 10, "v_grade": "SYN-V0", "font_grade": "SYN-F1"},
    {"difficulty_numeric": 11, "v_grade": "SYN-V0", "font_grade": "SYN-F2"},
    {"difficulty_numeric": 12, "v_grade": "SYN-V1", "font_grade": "SYN-F3"},
    {"difficulty_numeric": 14, "v_grade": "SYN-V2", "font_grade": "SYN-F4"},
]
SEMVER = re.compile(r"^\d+\.\d+\.\d+$")


def test_contract_structure():
    assert SEMVER.match(SCALE["version"])
    assert SCALE["status"]
    assert SCALE["source"]["table"] == "difficulty_grades"
    assert SCALE["source"]["hf_revision"]
    assert isinstance(SCALE["entries"], list)
    assert SCALE["ambiguity_policy"].strip()
    assert UNGRADED == "ungraded"


def test_entry_schema_matches_reference_helper():
    schema = SCALE["entry_schema"]
    assert set(schema["required"]) == gs.ENTRY_KEYS
    assert schema["properties"]["difficulty_numeric"]["type"] == "integer"
    assert schema["properties"]["v_grade"]["type"] == "string"
    assert schema["properties"]["font_grade"]["type"] == "string"
    assert schema["additionalProperties"] is False


def test_policy_text_states_tie_and_ungraded_rules():
    policy = SCALE["ambiguity_policy"]
    assert "LOWER" in policy and "'ungraded'" in policy and "never extrapolate" in policy


def test_blocked_status_implies_empty_unverified_entries():
    if SCALE["status"].startswith("BLOCKED"):
        assert REAL_ENTRIES == []
        assert SCALE["source"]["verified"] is False


def test_real_entries_valid_and_strictly_monotonic():
    gs.validate_entries(REAL_ENTRIES)  # vacuous while entries are empty


@pytest.mark.skipif(
    not REAL_ENTRIES,
    reason="entries empty: difficulty_grades not extracted (sqlite absent, BC-0002 blocked)",
)
def test_real_entries_populated_and_round_trip():
    assert SCALE["source"]["verified"] is True and SCALE["source"]["manifest"]
    v_numbers = [int(m.group(1)) for e in REAL_ENTRIES if (m := re.search(r"V(\d+)", e["v_grade"]))]
    assert v_numbers == sorted(v_numbers)
    for entry in REAL_ENTRIES:
        assert gs.select_entry(entry["difficulty_numeric"], REAL_ENTRIES) == entry


def test_synthetic_entries_validate():
    gs.validate_entries(SYNTH)


@pytest.mark.parametrize(
    "bad",
    [
        [SYNTH[1], SYNTH[0]],
        [SYNTH[0], SYNTH[0]],
        [{**SYNTH[0], "difficulty_numeric": 10.5}],
        [{**SYNTH[0], "difficulty_numeric": True}],
        [{"difficulty_numeric": 10, "v_grade": "SYN-V0"}],
        [{**SYNTH[0], "font_grade": ""}],
        [{**SYNTH[0], "extra": 1}],
    ],
)
def test_validate_rejects_bad_entries(bad):
    with pytest.raises((TypeError, ValueError)):
        gs.validate_entries(bad)


def test_round_trip_exact_values_map_to_own_entry():
    for entry in SYNTH:
        assert gs.select_entry(entry["difficulty_numeric"], SYNTH) is entry
        for system in ("v_grade", "font_grade"):
            label = gs.grade_label(entry["difficulty_numeric"], SYNTH, system, UNGRADED)
            assert label == entry[system]


@pytest.mark.parametrize(
    ("value", "expected_font"),
    [
        (10.4, "SYN-F1"),
        (10.5, "SYN-F1"),
        (10.51, "SYN-F2"),
        (12.9, "SYN-F3"),
        (13.0, "SYN-F3"),
        (13.01, "SYN-F4"),
        (9.5, "SYN-F1"),
        (14.5, "SYN-F4"),
    ],
)
def test_nearest_with_lower_tie_break(value, expected_font):
    assert gs.grade_label(value, SYNTH, "font_grade", UNGRADED) == expected_font


@pytest.mark.parametrize("value", [None, math.nan, "12", True, 9.49, 14.51, -1, 1e9])
def test_ungraded_values(value):
    assert gs.select_entry(value, SYNTH) is None
    assert gs.grade_label(value, SYNTH, "v_grade", UNGRADED) == UNGRADED


def test_empty_table_is_always_ungraded():
    assert gs.grade_label(12.0, [], "v_grade", UNGRADED) == UNGRADED


def test_mapping_is_monotonic_over_fine_grid():
    index = {e["difficulty_numeric"]: i for i, e in enumerate(SYNTH)}
    grid = [round(9.5 + step * 0.01, 2) for step in range(int((14.5 - 9.5) / 0.01) + 1)]
    selected = [index[gs.select_entry(x, SYNTH)["difficulty_numeric"]] for x in grid]
    assert selected == sorted(selected)
