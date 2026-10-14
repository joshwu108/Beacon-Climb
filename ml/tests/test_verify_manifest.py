"""Tests for ml/datasets/verify_manifest.py (BC-0002).

All fixtures are SYNTHETIC: fake uuids (synth-*) and fake frames tokens
(p900r9x). No Kilter data is used.
"""

from __future__ import annotations

import importlib.util
import json
import sqlite3
import sys
from pathlib import Path

import pytest

MODULE_PATH = Path(__file__).resolve().parents[1] / "datasets" / "verify_manifest.py"


def _load_module():
    spec = importlib.util.spec_from_file_location("verify_manifest", MODULE_PATH)
    module = importlib.util.module_from_spec(spec)
    sys.modules["verify_manifest"] = module
    spec.loader.exec_module(module)
    return module


vm = _load_module()

SPLIT_DDL = "CREATE TABLE {t} (uuid TEXT, angle INTEGER, frames TEXT, difficulty_numeric REAL)"
DISJOINT_ROWS = {
    "kilter_train": [
        ("synth-a", 40, "p900r91", 16.0),
        ("synth-a", 45, "p900r91", 17.5),
        ("synth-b", 40, "p901r92", 20.0),
    ],
    "kilter_val": [("synth-c", 30, "p902r93", 12.25)],
    "kilter_test": [("synth-d", 50, "p903r94", 24.0), ("synth-e", 25, "p904r91", 10.0)],
}


def _build_sqlite(path: Path, split_rows: dict) -> Path:
    conn = sqlite3.connect(path)
    for table, rows in split_rows.items():
        conn.execute(SPLIT_DDL.format(t=table))
        conn.executemany(f"INSERT INTO {table} VALUES (?, ?, ?, ?)", rows)
    conn.execute("CREATE TABLE difficulty_grades (difficulty INTEGER, boulder_name TEXT)")
    conn.executemany(
        "INSERT INTO difficulty_grades VALUES (?, ?)", [(10, "SYNTH-G1"), (12, "SYNTH-G2")]
    )
    conn.execute("CREATE TABLE placement_roles (id INTEGER, name TEXT)")
    conn.executemany(
        "INSERT INTO placement_roles VALUES (?, ?)", [(91, "synth-start"), (92, "synth-middle")]
    )
    conn.commit()
    conn.close()
    return path


@pytest.fixture()
def disjoint_db(tmp_path: Path) -> Path:
    return _build_sqlite(tmp_path / "synthetic.sqlite", DISJOINT_ROWS)


@pytest.fixture()
def overlapping_db(tmp_path: Path) -> Path:
    rows = {
        **DISJOINT_ROWS,
        "kilter_test": DISJOINT_ROWS["kilter_test"] + [("synth-a", 40, "p900r91", 16.0)],
    }
    return _build_sqlite(tmp_path / "overlap.sqlite", rows)


def _write_manifest(tmp_path: Path, db: Path) -> Path:
    out_dir = tmp_path / "manifests"
    out_dir.mkdir()
    assert vm.run([str(db), "--write", str(out_dir)]) == vm.EXIT_OK
    written = list(out_dir.glob("kilter-*.json"))
    assert len(written) == 1
    return written[0]


def test_measure_reports_counts_and_zero_overlap(disjoint_db):
    measured = vm.measure(disjoint_db)

    assert measured["tables"] == {
        "difficulty_grades": 2,
        "kilter_test": 2,
        "kilter_train": 3,
        "kilter_val": 1,
        "placement_roles": 2,
    }
    assert measured["uuid_counts"] == {"kilter_train": 2, "kilter_val": 1, "kilter_test": 2}
    assert set(measured["split_uuid_overlap"].values()) == {0}
    assert measured["bytes"] == disjoint_db.stat().st_size
    assert len(measured["sha256"]) == 64


def test_write_then_verify_ok(tmp_path, disjoint_db, capsys):
    manifest_path = _write_manifest(tmp_path, disjoint_db)
    manifest = json.loads(manifest_path.read_text())

    exit_code = vm.run([str(disjoint_db), str(manifest_path)])

    assert exit_code == vm.EXIT_OK
    assert capsys.readouterr().out.strip().endswith("OK")
    assert manifest["license_review_status"] == "LOCAL_RESEARCH_ONLY"
    assert manifest_path.name == f"kilter-{manifest['sha256'][:12]}.json"


def test_verify_reports_mismatch(tmp_path, disjoint_db, capsys):
    manifest_path = _write_manifest(tmp_path, disjoint_db)
    manifest = json.loads(manifest_path.read_text())
    tampered = {**manifest, "uuid_counts": {**manifest["uuid_counts"], "kilter_val": 999}}
    manifest_path.write_text(json.dumps(tampered))

    exit_code = vm.run([str(disjoint_db), str(manifest_path)])

    out = capsys.readouterr().out
    assert exit_code == vm.EXIT_MISMATCH
    assert "MISMATCH" in out and "uuid_counts" in out


def test_verify_rejects_pending_manifest_with_null_values(tmp_path, disjoint_db, capsys):
    pending = tmp_path / "pending.json"
    pending.write_text(
        json.dumps(
            {
                "status": "BLOCKED_SQLITE_ABSENT",
                "sha256": None,
                "bytes": None,
                "tables": None,
                "uuid_counts": None,
            }
        )
    )

    assert vm.run([str(disjoint_db), str(pending)]) == vm.EXIT_MISMATCH
    assert "BLOCKED_SQLITE_ABSENT" in capsys.readouterr().out


def test_missing_sqlite_exits_2(tmp_path, capsys):
    exit_code = vm.run([str(tmp_path / "absent.sqlite"), str(tmp_path / "m.json")])

    assert exit_code == vm.EXIT_INPUT_ERROR
    assert "sqlite not found" in capsys.readouterr().err


def test_missing_manifest_exits_2(tmp_path, disjoint_db, capsys):
    assert vm.run([str(disjoint_db), str(tmp_path / "nope.json")]) == vm.EXIT_INPUT_ERROR
    assert "manifest not found" in capsys.readouterr().err


def test_non_sqlite_file_exits_2(tmp_path, capsys):
    bogus = tmp_path / "bogus.sqlite"
    bogus.write_bytes(b"not a database" * 100)

    assert vm.run([str(bogus), "--write", str(tmp_path)]) == vm.EXIT_INPUT_ERROR
    assert "not a readable sqlite" in capsys.readouterr().err


def test_overlapping_splits_detected_on_verify(tmp_path, disjoint_db, overlapping_db, capsys):
    manifest_path = _write_manifest(tmp_path, disjoint_db)

    exit_code = vm.run([str(overlapping_db), str(manifest_path)])

    out = capsys.readouterr().out
    assert exit_code == vm.EXIT_MISMATCH
    assert "kilter_train&kilter_test: 1 shared uuid(s)" in out


def test_overlapping_splits_refuse_write(tmp_path, overlapping_db, capsys):
    out_dir = tmp_path / "out"
    out_dir.mkdir()

    assert vm.run([str(overlapping_db), "--write", str(out_dir)]) == vm.EXIT_MISMATCH
    assert list(out_dir.iterdir()) == []
    assert "REFUSING TO WRITE" in capsys.readouterr().err


def test_upstream_sha_mismatch_refuses_write(tmp_path, disjoint_db):
    template = tmp_path / "template.json"
    template.write_text(json.dumps({"upstream_reported": {"lfs_sha256": "0" * 64}}))
    out_dir = tmp_path / "out"
    out_dir.mkdir()

    exit_code = vm.run([str(disjoint_db), "--write", str(out_dir), "--template", str(template)])

    assert exit_code == vm.EXIT_MISMATCH
    assert list(out_dir.iterdir()) == []


def test_missing_split_table_exits_2(tmp_path, capsys):
    db = _build_sqlite(tmp_path / "partial.sqlite", {"kilter_train": DISJOINT_ROWS["kilter_train"]})

    assert vm.run([str(db), "--write", str(tmp_path)]) == vm.EXIT_INPUT_ERROR
    assert "split tables missing" in capsys.readouterr().err


def test_sqlite_is_not_modified(tmp_path, disjoint_db):
    before = vm.sha256_and_size(disjoint_db)

    vm.measure(disjoint_db)

    assert vm.sha256_and_size(disjoint_db) == before


def test_pending_manifest_in_repo_is_well_formed():
    pending = MODULE_PATH.parent / "manifests" / "kilter-PENDING.json"
    data = json.loads(pending.read_text())

    assert data["status"] == "BLOCKED_SQLITE_ABSENT"
    assert data["sha256"] is None and data["bytes"] is None
    assert data["license_review_status"] == "LOCAL_RESEARCH_ONLY"
