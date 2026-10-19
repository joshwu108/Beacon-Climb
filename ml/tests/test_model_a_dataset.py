"""Tests for ml/datasets/model_a (BC-0201). Every catalog here is a tiny SYNTHETIC fixture
built in tmp_path with the BC-0103 output layout; no Kilter data is used."""

from __future__ import annotations

import importlib.util
import json
import sqlite3
import sys
from datetime import UTC, datetime
from pathlib import Path

import pytest

ML_ROOT = Path(__file__).resolve().parents[1]


def _load(name: str, rel: str):
    spec = importlib.util.spec_from_file_location(name, ML_ROOT / rel)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


build = _load("model_a_build", "datasets/model_a/build.py")
access = _load("model_a_access", "datasets/model_a/access.py")

# Symmetric 3x2 board on raw coordinates: x in {0, 8, 16} (axis 8), y in {0, 8}.
HOLDS = {"a": (0, 0), "b": (8, 0), "c": (16, 0), "d": (0, 8), "e": (8, 8), "f": (16, 8)}


def _route(uuid, placements, variants):
    return {
        "type": "route",
        "provider_route_uuid": uuid,
        "board_id": "kilter-l1-s10",
        "name": None,
        "setter_pseudonym": None,
        "tags": [],
        "active_from": None,
        "active_to": None,
        "placements": [{"provider_hold_id": h, "role": r} for h, r in placements],
        "variants": [
            {"angle_deg": a, "grade_numeric": g, "grade_observation_count": n}
            for a, g, n in variants
        ],
    }


ROUTES = [
    ("t1", "train", [("a", "START"), ("e", "FINISH")], [(40, 18.0, 5), (20, 15.0, 1)]),
    ("v1", "val", [("e", "FINISH"), ("a", "START")], [(30, 17.0, 2)]),  # exact dup of t1
    ("v2", "val", [("c", "START"), ("e", "FINISH")], [(40, 19.0, 4)]),  # mirror of t1
    ("v3", "val", [("b", "START"), ("f", "FINISH")], [(40, 20.0, 9)]),  # kept
    ("s1", "test", [("b", "START"), ("f", "FINISH")], [(40, 21.0, 3)]),  # dup of v3
    ("s2", "test", [("d", "START"), ("f", "HAND")], [(35, 22.0, 7), (45, None, 0)]),
    ("s3", "test", [("a", "START"), ("e", "HAND")], [(40, 16.0, 2)]),  # role differs: kept
]


def _fixture(tmp_path: Path, routes=ROUTES, license="LOCAL_RESEARCH_ONLY"):
    catalog_dir = tmp_path / "data" / "catalog" / "kilter-x"
    catalog_dir.mkdir(parents=True)
    manifest = {
        "type": "manifest",
        "content_sha256": "0" * 64,
        "version": "v",
        "synthetic": False,
        "license_review_status": license,
    }
    board = {
        "type": "board",
        "holds": [
            {"provider_hold_id": h, "x": 0, "y": 0, "geometry_meta": {"x_raw": x, "y_raw": y}}
            for h, (x, y) in HOLDS.items()
        ],
    }
    lines = [manifest, board] + [_route(u, p, v) for u, _, p, v in routes]
    (catalog_dir / "catalog.jsonl").write_text("\n".join(json.dumps(x) for x in lines) + "\n")
    (catalog_dir / "splits.json").write_text(
        json.dumps({"splits": {u: s for u, s, _, _ in routes}})
    )
    db = tmp_path / "g.sqlite"
    con = sqlite3.connect(db)
    con.execute("CREATE TABLE difficulty_grades (difficulty INT, boulder_name TEXT)")
    con.executemany(
        "INSERT INTO difficulty_grades VALUES (?,?)",
        [(15, "5c/V2"), (16, "6a/V3"), (17, "6a+/V3"), (18, "6b/V4"), (20, "6c/V5"), (22, "7a/V6")],
    )
    con.commit()
    con.close()
    return catalog_dir, db


def _freeze(tmp_path, **kw):
    catalog_dir, db = _fixture(tmp_path, **kw)
    return build.freeze(
        catalog_dir, db, data_root=tmp_path / "data", manifest_dir=tmp_path / "manifests"
    )


def _rows(result, split):
    return [
        json.loads(x) for x in (Path(result["out_dir"]) / f"{split}.jsonl").read_text().splitlines()
    ]


def test_exact_and_mirror_duplicates_leave_later_splits(tmp_path):
    result = _freeze(tmp_path)
    assert result["dedupe_counts"] == {
        "val_removed_exact_duplicate": 1,
        "val_removed_mirror_duplicate": 1,
        "test_removed_exact_duplicate": 1,
        "variants_dropped_ungraded": 1,
    }
    assert {r["route_uuid"] for r in _rows(result, "val")} == {"v3"}
    assert {r["route_uuid"] for r in _rows(result, "test")} == {"s2", "s3"}
    assert len(_rows(result, "train")) == 2  # train keeps both angles of t1


def test_rows_carry_only_allowlisted_fields_and_raw_coordinates(tmp_path):
    result = _freeze(tmp_path)
    row = _rows(result, "train")[0]
    assert set(row) == set(build.ROW_FIELDS)
    assert row["holds"][0] == {"id": "a", "x": 0, "y": 0, "role": "START"}
    assert "ascents" in result["metadata_fields_not_features"]
    assert "ascents" not in result["feature_fields"]


def test_manifest_pins_hashes_and_counts(tmp_path):
    result = _freeze(tmp_path)
    committed = json.loads(Path(result["manifest"]).read_text())
    assert committed["sealed_split"] == "test"
    assert committed["sealed_sha256"] == committed["files_sha256"]["test"]
    assert committed["splits"]["test"]["by_grade"] == [
        {"difficulty": 16, "name": "6a/V3", "rows": 1},
        {"difficulty": 22, "name": "7a/V6", "rows": 1},
    ]
    assert committed["splits"]["train"]["rows_with_ascents_ge_3"] == 1
    assert "holds" not in json.dumps(committed["splits"])  # counts only, no rows


def test_grade_bin_rounds_halves_up():
    assert [build.grade_bin(g) for g in (16.49, 16.5, 17.5, 18.0)] == [16, 17, 18, 18]


def test_freeze_is_deterministic(tmp_path):
    first = _freeze(tmp_path / "a")
    second = _freeze(tmp_path / "b")
    assert first["files_sha256"] == second["files_sha256"]
    assert first["dataset_id"] == second["dataset_id"]


def test_refuses_non_local_research_catalog(tmp_path):
    with pytest.raises(build.Refused):
        _freeze(tmp_path, license="SYNTHETIC")


def test_refuses_route_without_split(tmp_path):
    catalog_dir, db = _fixture(tmp_path)
    (catalog_dir / "splits.json").write_text(json.dumps({"splits": {"t1": "train"}}))
    with pytest.raises(build.Refused, match="no official split"):
        build.freeze(catalog_dir, db, data_root=tmp_path / "data", manifest_dir=tmp_path / "m")


def test_test_split_is_sealed_and_accesses_are_logged(tmp_path):
    result = _freeze(tmp_path)
    manifest = json.loads(Path(result["manifest"]).read_text())
    root = tmp_path / "data"
    assert len(list(access.load_split(manifest, "train", data_root=root))) == 2
    with pytest.raises(access.SealViolation, match="sealed"):
        access.load_split(manifest, "test", data_root=root)
    assert access.list_test_accesses(manifest, root) == []
    now = datetime(2026, 10, 19, tzinfo=UTC)
    rows = list(
        access.load_split(
            manifest, "test", unseal_purpose="final eval gbm-v1", data_root=root, now=now
        )
    )
    assert len(rows) == 2
    assert access.list_test_accesses(manifest, root) == [
        {"at": "2026-10-19T00:00:00Z", "purpose": "final eval gbm-v1"}
    ]


def test_tampered_file_is_rejected(tmp_path):
    result = _freeze(tmp_path)
    manifest = json.loads(Path(result["manifest"]).read_text())
    path = Path(result["out_dir"]) / "val.jsonl"
    path.write_text(path.read_text() + "\n")
    with pytest.raises(access.SealViolation, match="does not match"):
        access.load_split(manifest, "val", data_root=tmp_path / "data")


def test_refreeze_keeps_the_test_access_log(tmp_path):
    result = _freeze(tmp_path)
    manifest = json.loads(Path(result["manifest"]).read_text())
    root = tmp_path / "data"
    access.load_split(manifest, "test", unseal_purpose="first look", data_root=root)
    catalog_dir = root / "catalog" / "kilter-x"
    build.freeze(
        catalog_dir, tmp_path / "g.sqlite", data_root=root, manifest_dir=tmp_path / "manifests"
    )
    assert [a["purpose"] for a in access.list_test_accesses(manifest, root)] == ["first look"]
    assert not (root / "datasets" / "model-a-staging").exists()
