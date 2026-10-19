"""Tests for the Deep Sets candidate (BC-0204) on SYNTHETIC data only."""

from __future__ import annotations

import json

import pytest

torch = pytest.importorskip("torch")
pytest.importorskip("sklearn")

from test_model_a_train import CONFIG as BASE_CONFIG
from test_model_a_train import _synthetic_frozen

from beacon_ml.train import deepsets, run, run_deepsets


def _row(holds, angle=40, grade=18.0):
    return {"holds": holds, "angle_deg": angle, "grade": grade}


H = [
    {"id": "a", "x": 0, "y": 4, "role": "START"},
    {"id": "b", "x": 60, "y": 80, "role": "HAND"},
    {"id": "c", "x": 100, "y": 150, "role": "FINISH"},
    {"id": "d", "x": 20, "y": 4, "role": "FOOT"},
]


def test_encoder_is_order_invariant_and_ignores_padding():
    vocab = deepsets.TokenVocab.fit([_row(H)])
    torch.manual_seed(0)
    model = deepsets.DeepSets(vocab.size, hidden=16)
    a = deepsets.encode([_row(H)], vocab, max_holds=8)
    b = deepsets.encode([_row(list(reversed(H)))], vocab, max_holds=6)
    out_a = model(a.hold_ids, a.numeric, a.mask, a.angle)
    out_b = model(b.hold_ids, b.numeric, b.mask, b.angle)
    assert torch.allclose(out_a, out_b, atol=1e-5)


def test_unseen_hold_maps_to_reserved_index_zero():
    vocab = deepsets.TokenVocab.fit([_row(H[:2])])
    enc = deepsets.encode([_row(H)], vocab, max_holds=5)
    assert enc.hold_ids[0].tolist() == [1, 2, 0, 0, 0]
    assert enc.mask[0].tolist() == [1, 1, 1, 1, 0]


CONFIG = {
    **BASE_CONFIG,
    "max_holds": 12,
    "epochs": 6,
    "patience": 2,
    "batch": 64,
    "lr": 0.003,
    "weight_decay": 0.0,
    "hidden": 32,
    "threads": 1,
    "sweep": [{}, {"dropout": 0.1}],
}


def test_val_then_test_end_to_end(tmp_path):
    manifest = _synthetic_frozen(tmp_path)
    names = run.grade_names(manifest)
    root, models = tmp_path / "data", tmp_path / "models"
    val = run_deepsets.stage_val(CONFIG, manifest, names, "v1", data_root=root, models_dir=models)
    assert run.data.access.list_test_accesses(manifest, root) == []
    assert val["results"]["deepsets"]["all"]["n"] > 0
    assert len(val["trials"]) == 2
    best = min(val["trials"], key=lambda t: t["val_mae"])
    assert val["candidate"]["trial_index"] == best["index"]
    out = tmp_path / "out"
    out.mkdir()
    run.stage_val(BASE_CONFIG, manifest, names, out, data_root=root)
    test = run_deepsets.stage_test(
        CONFIG,
        manifest,
        names,
        "t1",
        models / "v1" / "candidate.json",
        out / "selection.json",
        data_root=root,
    )
    assert len(run.data.access.list_test_accesses(manifest, root)) == 1
    assert test["artifact"]["state_dict_sha256"] == test["candidate"]["state_dict_sha256"]
    assert set(test["results"]) == {"deepsets", next(k for k in test["results"] if k != "deepsets")}


def test_tampered_checkpoint_is_refused(tmp_path):
    manifest = _synthetic_frozen(tmp_path)
    models = tmp_path / "models"
    run_deepsets.stage_val(
        CONFIG,
        manifest,
        run.grade_names(manifest),
        "v1",
        data_root=tmp_path / "data",
        models_dir=models,
    )
    candidate = json.loads((models / "v1" / "candidate.json").read_text())
    (models / "v1" / "state_dict.pt").write_bytes(b"tampered")
    assert candidate["state_dict_sha256"]
    with pytest.raises(SystemExit, match="does not match"):
        run_deepsets._load_candidate(models / "v1" / "candidate.json")
