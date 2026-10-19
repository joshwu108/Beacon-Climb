"""Tests for ml/datasets/kilter/extract.py (BC-0103).

Every database here is a tiny SYNTHETIC sqlite built in tmp_path with the same table and column
names as the Kilter splits file; no Kilter data is used or committed.
"""

from __future__ import annotations

import hashlib
import importlib.util
import json
import sqlite3
import sys
from datetime import UTC, datetime
from pathlib import Path

import pytest

ML_ROOT = Path(__file__).resolve().parents[1]
MODULE_PATH = ML_ROOT / "datasets" / "kilter" / "extract.py"
SECRET = "SECRET-PERSONAL-VALUE"
SIGNED = "[x] I accept use (a) local research import under the conditions above.  Signed: t\n"
NOW = datetime(2026, 10, 19, tzinfo=UTC)


def _load():
    spec = importlib.util.spec_from_file_location("kilter_extract", MODULE_PATH)
    module = importlib.util.module_from_spec(spec)
    sys.modules["kilter_extract"] = module
    spec.loader.exec_module(module)
    return module


ex = _load()

CLIMB_DDL = """CREATE TABLE {t} (uuid TEXT, angle REAL, name TEXT, description TEXT,
  difficulty_numeric REAL, ascensionist_count REAL, frames TEXT, frames_count INTEGER,
  is_listed INTEGER, created_at TEXT, setter_username TEXT, fa_username TEXT, beta_links TEXT)"""


def _climb(uuid, angle, frames, grade=18.0, ascents=5.0, frames_count=1, listed=1):
    return (
        uuid,
        angle,
        SECRET,
        SECRET,
        grade,
        ascents,
        frames,
        frames_count,
        listed,
        "2020-01-02 03:04:05.000001",
        SECRET,
        SECRET,
        SECRET,
    )


def _make_db(path: Path, train=(), val=(), test=()) -> Path:
    con = sqlite3.connect(path)
    con.execute("CREATE TABLE placement_roles (id INT, product_id INT, position INT, name TEXT)")
    con.executemany(
        "INSERT INTO placement_roles VALUES (?,?,?,?)",
        [
            (12, 1, 1, "start"),
            (13, 1, 2, "middle"),
            (14, 1, 3, "finish"),
            (15, 1, 4, "foot"),
            (20, 2, 1, "start"),
            (36, 6, 1, "cyan"),
        ],
    )
    con.execute("CREATE TABLE holes (id INT, product_id INT, name TEXT, x INT, y INT)")
    con.executemany(
        "INSERT INTO holes VALUES (?,?,?,?,?)",
        [(1, 1, "a", -20, 4), (2, 1, "b", 164, 176), (3, 1, "c", 72, 90), (4, 2, "d", 0, 0)],
    )
    con.execute("CREATE TABLE placements (id INT, layout_id INT, hole_id INT, set_id INT)")
    con.executemany(
        "INSERT INTO placements VALUES (?,?,?,?)",
        [(101, 1, 1, 1), (102, 1, 2, 20), (103, 1, 3, 1), (104, 2, 4, 21)],
    )
    for table, rows in (("kilter_train", train), ("kilter_val", val), ("kilter_test", test)):
        con.execute(CLIMB_DDL.format(t=table))
        con.executemany(f"INSERT INTO {table} VALUES ({','.join('?' * 13)})", rows)
    con.commit()
    con.close()
    return path


def _setup(tmp_path, train=(), val=(), test=(), signed=True, license="LOCAL_RESEARCH_ONLY"):
    db = _make_db(tmp_path / "k.sqlite", train, val, test)
    manifest = tmp_path / "m.json"
    manifest.write_text(
        json.dumps(
            {
                "sha256": hashlib.sha256(db.read_bytes()).hexdigest(),
                "license_review_status": license,
            }
        )
    )
    audit = tmp_path / "audit.md"
    audit.write_text(SIGNED if signed else "[ ] I accept use (a) local research import\n")
    data_root = tmp_path / "data"
    data_root.mkdir()
    return db, manifest, audit, data_root


def _run(tmp_path, ack=True, out=None, **kw):
    db, manifest, audit, data_root = _setup(tmp_path, **kw)
    return ex.extract(db, manifest, out, ack, audit=audit, data_root=data_root, now=NOW)


def _lines(summary) -> list[dict]:
    return [json.loads(x) for x in Path(summary["catalog"]).read_text().splitlines()]


BASIC = {
    "train": [
        _climb("u1", 40.0, "p101r12p103r13p102r14"),
        _climb("u1", 20.0, "p101r12p103r13p102r14"),
    ],
    "val": [_climb("u2", 40.0, "p101r20p103r15", listed=0)],
    "test": [_climb("u3", 30.0, "p103r12p102r14", grade=None)],
}


def test_refuses_without_acknowledge(tmp_path):
    with pytest.raises(ex.Refused, match="acknowledge"):
        _run(tmp_path, ack=False, **BASIC)


def test_refuses_unsigned_audit(tmp_path):
    with pytest.raises(ex.Refused, match="signed"):
        _run(tmp_path, signed=False, **BASIC)


def test_refuses_wrong_license_status(tmp_path):
    with pytest.raises(ex.Refused, match="LOCAL_RESEARCH_ONLY"):
        _run(tmp_path, license="SYNTHETIC", **BASIC)


def test_refuses_sha_mismatch(tmp_path):
    db, manifest, audit, data_root = _setup(tmp_path, **BASIC)
    db.write_bytes(db.read_bytes() + b"x")
    with pytest.raises(ex.Refused, match="sha256"):
        ex.extract(db, manifest, None, True, audit=audit, data_root=data_root, now=NOW)


def test_refuses_out_dir_outside_data_root(tmp_path):
    with pytest.raises(ex.Refused, match="inside"):
        _run(tmp_path, out=tmp_path / "elsewhere", **BASIC)


def test_cli_exit_code_two_when_refused(tmp_path, capsys):
    db, manifest, _, _ = _setup(tmp_path, **BASIC)
    assert ex.main(["--db", str(db), "--manifest", str(manifest)]) == ex.EXIT_REFUSED
    assert "REFUSED" in capsys.readouterr().err


def test_catalog_shape_and_role_mapping_by_name(tmp_path):
    summary = _run(tmp_path, **BASIC)
    manifest, board, *routes = _lines(summary)
    assert manifest["type"] == "manifest" and manifest["synthetic"] is False
    assert manifest["license_review_status"] == "LOCAL_RESEARCH_ONLY"
    assert manifest["record_counts"] == {"boards": 1, "routes": 3}
    assert board["available_angles"] == [20, 30, 40]
    assert [h["provider_hold_id"] for h in board["holds"]] == ["p101", "p102", "p103"]
    by_id = {r["provider_route_uuid"]: r for r in routes}
    assert [p["role"] for p in by_id["u1"]["placements"]] == ["START", "HAND", "FINISH"]
    # role id 20 belongs to another product but is named "start": mapped by name
    assert [p["role"] for p in by_id["u2"]["placements"]] == ["START", "FOOT"]
    assert [v["angle_deg"] for v in by_id["u1"]["variants"]] == [20, 40]
    assert by_id["u2"]["variants"][0]["validation_status"] == "RETIRED"
    assert by_id["u3"]["variants"][0] == {
        "angle_deg": 30,
        "grade_numeric": None,
        "grade_observation_count": 0,
        "validation_status": "ACTIVE",
    }
    assert by_id["u1"]["active_from"] == "2020-01-02T03:04:05.000001Z"


def test_coordinates_are_normalised_with_raw_values_kept(tmp_path):
    summary = _run(tmp_path, **BASIC)
    holds = {h["provider_hold_id"]: h for h in _lines(summary)[1]["holds"]}
    assert (holds["p101"]["x"], holds["p101"]["y"]) == (0, 0)
    assert (holds["p102"]["x"], holds["p102"]["y"]) == (1, 1)
    assert holds["p103"]["geometry_meta"]["x_raw"] == 72
    assert summary["normalisation"] == {"x_min": -20, "x_span": 184, "y_min": 4, "y_span": 172}


def test_no_personal_or_free_text_value_reaches_any_output(tmp_path):
    summary = _run(tmp_path, **BASIC)
    out_dir = Path(summary["catalog"]).parent
    for path in out_dir.iterdir():
        assert SECRET not in path.read_text(), path.name
    for route in _lines(summary)[2:]:
        assert route["name"] is None and route["setter_pseudonym"] is None


def test_rejections_are_counted_not_silently_dropped(tmp_path):
    summary = _run(
        tmp_path,
        train=[
            _climb("ok", 40.0, "p101r12p102r14"),
            _climb("mf", 40.0, "p101r12,p102r14", frames_count=2),
            _climb("bad", 40.0, "p101r12xx"),
            _climb("unk", 40.0, "p101r12p104r14"),
            _climb("role", 40.0, "p101r36"),
            _climb("frac", 42.5, "p101r12"),
        ],
    )
    assert summary["routes"] == 1
    assert summary["rows_rejected"] == {
        "MALFORMED_FRAMES": 1,
        "MULTI_FRAME": 1,
        "NON_INTEGER_ANGLE": 1,
        "UNKNOWN_PLACEMENT": 1,
        "UNKNOWN_ROLE": 1,
    }


def test_duplicate_token_same_role_is_dropped_different_roles_kept(tmp_path):
    summary = _run(tmp_path, train=[_climb("d", 40.0, "p101r12p101r12p103r13p103r15")])
    route = _lines(summary)[2]
    assert [(p["provider_hold_id"], p["role"]) for p in route["placements"]] == [
        ("p101", "START"),
        ("p103", "HAND"),
        ("p103", "FOOT"),
    ]
    assert summary["duplicate_tokens_dropped"] == 1


def test_splits_sidecar_and_uuid_split_conflict(tmp_path):
    summary = _run(
        tmp_path,
        train=[_climb("x", 40.0, "p101r12")],
        test=[_climb("x", 20.0, "p101r12"), _climb("y", 20.0, "p101r12")],
    )
    splits = json.loads((Path(summary["catalog"]).parent / "splits.json").read_text())["splits"]
    assert splits == {"x": "train", "y": "test"}
    assert summary["rows_rejected"] == {"UUID_IN_TWO_SPLITS": 1}


def test_content_sha256_and_determinism(tmp_path):
    runs = []
    for name in ("a", "b"):
        (tmp_path / name).mkdir()
        runs.append(_run(tmp_path / name, **BASIC))
    first, second = runs
    assert first["catalog_file_sha256"] == second["catalog_file_sha256"]
    lines = Path(first["catalog"]).read_text().splitlines()
    body = [ex.canonical_json(json.loads(x)) for x in lines[1:]]
    assert json.loads(lines[0])["content_sha256"] == ex.content_sha256(body)
