"""Tests for ml/datasets/synthetic/generate.py (BC-0104). Standard library only.

Everything here is SYNTHETIC: the generator invents the board, routes and grades from a
seeded RNG. The pinned sha256 below is the hash of the committed fixture bytes.
"""

from __future__ import annotations

import hashlib
import importlib.util
import json
import sys
from pathlib import Path

import pytest

ML_ROOT = Path(__file__).resolve().parents[1]
MODULE_PATH = ML_ROOT / "datasets" / "synthetic" / "generate.py"
FIXTURE = ML_ROOT / "datasets" / "synthetic" / "fixtures" / "demo-board-v1.jsonl"
README = ML_ROOT / "datasets" / "synthetic" / "README.md"
SEED = 42
ROUTES = 300
# sha256 of the committed fixture file bytes (seed 42, 300 routes). To update after an
# intentional generator change: run `make synthetic-board`, then
# `shasum -a 256 ml/datasets/synthetic/fixtures/demo-board-v1.jsonl` and paste the hex here.
PINNED_SHA256 = "c320f15f4413a56901fd2928e24fbd5bf00748dc93f1986f481fb723b7738f7e"
MAX_FIXTURE_BYTES = 1_000_000


def _load_module():
    spec = importlib.util.spec_from_file_location("synthetic_generate", MODULE_PATH)
    module = importlib.util.module_from_spec(spec)
    sys.modules["synthetic_generate"] = module
    spec.loader.exec_module(module)
    return module


gen = _load_module()


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _records(data: bytes) -> list[dict]:
    text = data.decode("utf-8")
    assert text.endswith("\n") and "\r" not in text
    return [json.loads(line) for line in text[:-1].split("\n")]


def _generate(tmp_path: Path, name: str, seed: int = SEED) -> bytes:
    out = tmp_path / name
    assert gen.main(["--seed", str(seed), "--routes", str(ROUTES), "--out", str(out)]) == 0
    return out.read_bytes()


def test_generation_is_deterministic(tmp_path):
    first = _generate(tmp_path, "a.jsonl")
    second = _generate(tmp_path, "b.jsonl")
    assert first == second


def test_committed_fixture_matches_pinned_sha256_and_fresh_generation(tmp_path):
    fixture_bytes = FIXTURE.read_bytes()
    assert _sha256(fixture_bytes) == PINNED_SHA256
    assert _sha256(_generate(tmp_path, "fresh.jsonl")) == PINNED_SHA256
    assert len(fixture_bytes) < MAX_FIXTURE_BYTES


def test_different_seed_gives_different_bytes(tmp_path):
    assert _generate(tmp_path, "s42.jsonl", 42) != _generate(tmp_path, "s43.jsonl", 43)


def test_manifest_first_and_synthetic():
    manifest = _records(FIXTURE.read_bytes())[0]
    assert manifest["type"] == "manifest"
    assert manifest["schema_version"] == 1
    assert manifest["synthetic"] is True
    assert manifest["license_review_status"] == "SYNTHETIC"
    assert manifest["source"] == "synthetic-demo"
    assert manifest["version"] == "demo-board-v1"
    assert "SYNTHETIC" in manifest["notes"]


def test_record_counts_and_order():
    records = _records(FIXTURE.read_bytes())
    manifest, body = records[0], records[1:]
    types = [r["type"] for r in body]
    boards = types.count("board")
    assert boards == manifest["record_counts"]["boards"] == 1
    assert types.count("route") == manifest["record_counts"]["routes"] == ROUTES
    assert types == ["board"] * boards + ["route"] * ROUTES, "boards precede routes"


def test_content_sha256_rule():
    text = FIXTURE.read_text(encoding="utf-8")
    lines = text[:-1].split("\n")
    manifest = json.loads(lines[0])
    canonical = [gen.canonical_json(json.loads(line)) for line in lines[1:]]
    assert canonical == lines[1:], "generator writes canonical lines"
    assert gen.content_sha256(canonical) == manifest["content_sha256"]
    assert _sha256("\n".join(canonical).encode("utf-8")) == manifest["content_sha256"]


def test_canonical_json_rules():
    assert gen.canonical_json({"b": 1.0, "a": 1}) == '{"a":1,"b":1}'  # ADR-0002 fixture
    assert (
        gen.canonical_json({"x": 0.0, "y": 0.042, "n": None, "t": True})
        == '{"n":null,"t":true,"x":0,"y":0.042}'
    )
    assert gen.canonical_json(["é", "\n", 12.5]) == '["é","\\n",12.5]'
    assert gen.canonical_json({"דּ": 1, "\U0001f600": 2, "€": 3}) == '{"€":3,"\U0001f600":2,"דּ":1}'
    with pytest.raises(ValueError):
        gen.canonical_json(1e-7)


def test_placements_reference_known_holds_and_roles():
    records = _records(FIXTURE.read_bytes())
    board = records[1]
    hold_ids = {h["provider_hold_id"] for h in board["holds"]}
    assert len(hold_ids) == len(board["holds"]) == 144
    for route in records[2:]:
        assert route["board_id"] == board["board_id"]
        placements = route["placements"]
        assert 4 <= len(placements) <= 25
        roles = [p["role"] for p in placements]
        assert "START" in roles and "FINISH" in roles
        assert set(roles) <= {"START", "HAND", "FOOT", "FINISH"}
        assert all(p["provider_hold_id"] in hold_ids for p in placements)
        assert len({p["provider_hold_id"] for p in placements}) == len(placements)
        assert [p["placement_order"] for p in placements] == list(range(len(placements)))


def test_route_ids_have_no_colon_and_variants_are_sane():
    records = _records(FIXTURE.read_bytes())
    board = records[1]
    available = set(board["available_angles"])
    for route in records[2:]:
        assert ":" not in route["provider_route_uuid"]
        assert " " not in route["provider_route_uuid"]
        angles = [v["angle_deg"] for v in route["variants"]]
        assert 1 <= len(angles) <= 3 and len(set(angles)) == len(angles)
        assert set(angles) <= available
        for v in route["variants"]:
            assert v["validation_status"] in {"ACTIVE", "RETIRED"}
            if v["grade_numeric"] is None:
                assert v["grade_observation_count"] == 0
            else:
                assert 10.0 <= v["grade_numeric"] <= 33.0
                assert 1 <= v["grade_observation_count"] <= 400


def test_board_geometry_and_hold_coordinates():
    board = _records(FIXTURE.read_bytes())[1]
    assert board["board_id"] == "synthetic-demo-1"
    assert board["synthetic"] is True
    assert board["geometry"] == {"units": "NORMALIZED", "width": 1, "height": 1, "y_axis": "UP"}
    assert board["available_angles"] == [20, 30, 40, 45, 50]
    for hold in board["holds"]:
        assert 0 < hold["x"] < 1 and 0 < hold["y"] < 1
        assert round(hold["x"], 3) == hold["x"] and round(hold["y"], 3) == hold["y"]


def test_readme_says_synthetic_up_front():
    head = README.read_text(encoding="utf-8").splitlines()[:5]
    assert any("SYNTHETIC" in line for line in head)
