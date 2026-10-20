"""BC-0213: the Modal harness's gate, upload plan and report helpers (no Modal, no token)."""

from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path

import pytest

from beacon_ml.remote import modal_train as mt

SIGNED = "[x] I accept use (a) local research import under the conditions above.\n"


def _manifest(tmp_path: Path, license="LOCAL_RESEARCH_ONLY") -> dict:
    folder = tmp_path / "datasets" / "model-a-000000000000"
    folder.mkdir(parents=True)
    hashes = {}
    for split in ("train", "val", "test"):
        path = folder / f"{split}.jsonl"
        path.write_text(json.dumps({"split": split}) + "\n")
        hashes[split] = hashlib.sha256(path.read_bytes()).hexdigest()
    return {
        "dataset_id": "model-a-000000000000",
        "files_sha256": hashes,
        "sealed_split": "test",
        "license_review_status": license,
    }


def test_importing_the_harness_does_not_import_modal():
    import subprocess

    code = "import sys, beacon_ml.remote.modal_train; print('modal' in sys.modules)"
    out = subprocess.run(
        [sys.executable, "-I", "-c", code], capture_output=True, text=True, check=True
    ).stdout.strip()
    assert out == "False"


def test_non_synthetic_refused_without_rights_flag(tmp_path):
    audit = tmp_path / "audit.md"
    audit.write_text(SIGNED)
    with pytest.raises(mt.Refused, match="--rights-signed"):
        mt.check_gate({"license_review_status": "LOCAL_RESEARCH_ONLY"}, False, audit)


def test_non_synthetic_refused_without_signed_audit(tmp_path):
    audit = tmp_path / "audit.md"
    audit.write_text("[ ] I accept use (a) local research import\n")
    with pytest.raises(mt.Refused, match="signed"):
        mt.check_gate({"license_review_status": "LOCAL_RESEARCH_ONLY"}, True, audit)


def test_signed_audit_and_flag_pass_and_synthetic_always_passes(tmp_path):
    audit = tmp_path / "audit.md"
    audit.write_text(SIGNED)
    mt.check_gate({"license_review_status": "LOCAL_RESEARCH_ONLY"}, True, audit)
    mt.check_gate({"license_review_status": "SYNTHETIC"}, False, tmp_path / "missing.md")


def test_upload_plan_never_includes_the_sealed_test_split(tmp_path):
    plan = mt.upload_plan(_manifest(tmp_path), data_root=tmp_path)
    assert [remote.rsplit("/", 1)[1] for _, remote in plan] == ["train.jsonl", "val.jsonl"]
    assert [local.name for local, _ in plan] == ["train.jsonl", "val.jsonl"]


def test_upload_plan_refuses_a_tampered_file(tmp_path):
    manifest = _manifest(tmp_path)
    (tmp_path / "datasets" / manifest["dataset_id"] / "val.jsonl").write_text("changed\n")
    with pytest.raises(mt.Refused, match="does not match"):
        mt.upload_plan(manifest, data_root=tmp_path)


def test_remote_manifest_has_no_test_hash():
    remote = mt.remote_manifest(
        {
            "dataset_id": "x",
            "files_sha256": {"train": "a", "val": "b", "test": "c"},
            "sealed_split": "test",
        }
    )
    assert remote["files_sha256"] == {"train": "a", "val": "b"}


def test_cost_estimate_only_from_a_configured_rate():
    assert mt.cost_estimate({}, 3600)["estimated_usd"] is None
    assert mt.cost_estimate({"usd_per_hour": 0.6}, 1800)["estimated_usd"] == 0.3


def test_cli_refuses_before_any_upload_without_rights_flag(tmp_path, capsys, monkeypatch):
    config = tmp_path / "c.json"
    config.write_text(json.dumps({"manifest": "datasets/manifests/model-a-100d08f359ca.json"}))
    monkeypatch.setitem(sys.modules, "modal", None)  # any `import modal` would now fail loudly
    assert mt.main(["--config", str(config)]) == 2
    assert "nothing uploaded" in capsys.readouterr().err
