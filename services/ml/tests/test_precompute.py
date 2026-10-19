"""BC-0207: precompute grouping and output rows (no database needed)."""

from __future__ import annotations

import pytest
from beacon_ml import registry

from app import precompute

ROWS = [
    ("v1", 40, "p1", 0.0, 4.0, "START"),
    ("v1", 40, "p2", 60.0, 90.0, "HAND"),
    ("v1", 40, "p3", 100.0, 150.0, "FINISH"),
    ("v2", 20, "p1", 0.0, 4.0, "START"),
    ("v2", 20, "p3", 100.0, 150.0, "FINISH"),
]


def test_group_variants_builds_one_input_per_variant_in_order():
    grouped = list(precompute.group_variants(ROWS))
    assert [v for v, _ in grouped] == ["v1", "v2"]
    assert grouped[0][1]["angle_deg"] == 40 and len(grouped[0][1]["holds"]) == 3
    assert grouped[1][1]["holds"][1] == {"id": "p3", "x": 100.0, "y": 150.0, "role": "FINISH"}


def test_missing_raw_coordinates_refuse_the_board():
    with pytest.raises(precompute.Refused, match="raw coordinates"):
        list(precompute.group_variants([("v1", 40, "h001", None, None, "START")]))


def test_batches_split_and_keep_remainder():
    assert [len(b) for b in precompute.batches(iter(range(5)), 2)] == [2, 2, 1]


def test_output_rows_gbm_has_no_embedding(registry_root):
    verified = registry.verify(registry_root / "model-a-gbm-t")
    rows = precompute.output_rows(list(precompute.group_variants(ROWS)), verified)
    assert [r[0] for r in rows] == ["v1", "v2"]
    assert all(r[1] == "model-a-gbm-t" and r[3] is None for r in rows)
    assert all(round(r[2], 3) == r[2] for r in rows)


def test_output_rows_deepsets_has_32_dim_embedding(registry_root):
    verified = registry.verify(registry_root / "model-a-ds-t")
    rows = precompute.output_rows(list(precompute.group_variants(ROWS)), verified)
    assert all(len(r[3]) == 32 for r in rows)
