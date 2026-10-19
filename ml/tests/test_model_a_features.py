"""Tests for beacon_ml.features.model_a (BC-0202). Synthetic inputs only."""

from __future__ import annotations

import json
import math
from pathlib import Path

import pytest

from beacon_ml.features import model_a as fa

FIXTURES = Path(fa.__file__).parent / "fixtures" / "model_a_features.v1.json"
TOLERANCE = 1e-9


def _h(i, x, y, role):
    return {"id": f"h{i}", "x": x, "y": y, "role": role}


def test_golden_fixtures_match_within_tolerance():
    data = json.loads(FIXTURES.read_text())
    assert data["feature_version"] == fa.FEATURE_VERSION
    assert data["features"] == list(fa.GEOMETRY_FEATURES)
    for case in data["cases"]:
        got = fa.geometry_vector(case["holds"], case["angle_deg"])
        assert len(got) == len(fa.GEOMETRY_FEATURES)
        for name, g, e in zip(fa.GEOMETRY_FEATURES, got, case["expected"], strict=True):
            assert abs(g - e) <= TOLERANCE, (case["name"], name, g, e)


def test_three_four_five_triangle_by_hand():
    v = dict(
        zip(
            fa.GEOMETRY_FEATURES,
            fa.geometry_vector([_h(1, 0, 0, "START"), _h(2, 30, 40, "FINISH")], 40),
            strict=True,
        )
    )
    assert v["start_finish_dist"] == 50.0
    assert v["hand_x_std"] == 15.0
    assert math.isclose(v["hand_density"], 2 / 1200)
    assert v["foot_y_mean"] == fa.MISSING


def test_hold_order_does_not_change_features():
    holds = [
        _h(1, 10, 20, "START"),
        _h(2, 20, 60, "HAND"),
        _h(3, 40, 130, "FINISH"),
        _h(4, 15, 4, "FOOT"),
    ]
    assert fa.geometry_vector(holds, 30) == fa.geometry_vector(list(reversed(holds)), 30)


def test_angle_is_the_only_angle_dependence():
    holds = [_h(1, 10, 20, "START"), _h(2, 40, 130, "FINISH")]
    a, b = fa.geometry_vector(holds, 20), fa.geometry_vector(holds, 50)
    diffs = [n for n, x, y in zip(fa.GEOMETRY_FEATURES, a, b, strict=True) if x != y]
    assert diffs == ["angle_deg"]


def test_hold_vocabulary_hand_and_foot_columns_and_unseen_holds():
    train = [{"holds": [_h(1, 0, 0, "START"), _h(2, 0, 0, "FOOT")]}]
    vocab = fa.HoldVocabulary.fit(train)
    assert vocab.hold_ids == ["h1", "h2"] and vocab.width == 4
    assert vocab.columns([_h(1, 0, 0, "HAND"), _h(2, 0, 0, "FOOT"), _h(9, 0, 0, "HAND")]) == [0, 3]


@pytest.mark.parametrize("x,y,expected", [(-20, 4, (0.0, 0.0)), (164, 176, (1.0, 1.0))])
def test_normalized_xy_frame(x, y, expected):
    assert fa.normalized_xy(x, y) == expected
