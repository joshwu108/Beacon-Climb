"""Tests for beacon_ml.registry (BC-0205), using models trained on SYNTHETIC data only."""

from __future__ import annotations

import json
from datetime import UTC, datetime
from pathlib import Path

import pytest

np = pytest.importorskip("numpy")
pytest.importorskip("sklearn")

from test_model_a_train import _synthetic_frozen

from beacon_ml import registry
from beacon_ml.train import data
from beacon_ml.train.baselines import Gbm

SCHEMA = Path(__file__).resolve().parents[2] / "contracts" / "ml" / "model-manifest.v1.schema.json"
NOW = datetime(2026, 10, 19, tzinfo=UTC)


def _trained_gbm_dir(tmp_path: Path) -> Path:
    manifest = _synthetic_frozen(tmp_path)
    train = data.load(manifest, "train", data_root=tmp_path / "data")
    model = Gbm(use_holds=True, learning_rate=0.1, max_leaf_nodes=15, max_iter=30, seed=1).fit(
        train
    )
    source = tmp_path / "trained"
    source.mkdir()
    import pickle

    (source / "model.pkl").write_bytes(pickle.dumps(model))
    return source


def _package(tmp_path: Path, version="model-a-gbm-test1") -> Path:
    return registry.package(
        kind="sklearn-hgb",
        source_dir=_trained_gbm_dir(tmp_path),
        model_version=version,
        training_manifest_id="model-a-000000000000",
        training_run_id="test",
        metrics_ref="docs/evidence/none",
        registry_root=tmp_path / "registry",
        now=NOW,
    )


def test_packaged_model_verifies_ready_and_predicts(tmp_path):
    folder = _package(tmp_path)
    result = registry.verify(folder)
    assert result.status == "READY", result.reason
    preds = result.predict(registry.fixture_rows())
    assert preds.shape == (len(registry.fixture_rows()),)


def test_manifest_has_exactly_the_contract_keys(tmp_path):
    manifest = json.loads((_package(tmp_path) / "manifest.json").read_text())
    schema = json.loads(SCHEMA.read_text())
    assert set(manifest) == set(schema["properties"])
    assert set(schema["required"]) <= set(manifest)
    assert manifest["artifact_digest"] == registry.artifact_digest(manifest["files"])


def test_versions_are_immutable(tmp_path):
    _package(tmp_path)
    with pytest.raises(FileExistsError):
        registry.package(
            kind="sklearn-hgb",
            source_dir=tmp_path / "trained",
            model_version="model-a-gbm-test1",
            training_manifest_id="x",
            training_run_id="x",
            metrics_ref="x",
            registry_root=tmp_path / "registry",
        )


def test_tampered_artifact_is_rejected_before_unpickling(tmp_path):
    folder = _package(tmp_path)
    (folder / "model.pkl").write_bytes(b"not the model")
    result = registry.verify(folder)
    assert result.status == "REJECTED" and "sha256 mismatch" in result.reason


def test_changed_fixture_expectation_is_rejected(tmp_path):
    folder = _package(tmp_path)
    manifest = json.loads((folder / "manifest.json").read_text())
    manifest["fixture"]["expected_grade"][0] += 0.5
    (folder / "manifest.json").write_text(json.dumps(manifest))
    result = registry.verify(folder)
    assert result.status == "REJECTED" and "fixture" in result.reason


def test_wrong_input_schema_version_is_rejected(tmp_path):
    folder = _package(tmp_path)
    manifest = json.loads((folder / "manifest.json").read_text())
    manifest["input_schema_version"] = "model-a-features-v0"
    (folder / "manifest.json").write_text(json.dumps(manifest))
    assert registry.verify(folder).reason == "input_schema_version mismatch"


def test_missing_manifest_is_rejected(tmp_path):
    assert registry.verify(tmp_path).status == "REJECTED"


def test_synthetic_smoke_model_packages_ready_and_labelled_synthetic(tmp_path):
    from beacon_ml import smoke

    folder = smoke.build(tmp_path)
    result = registry.verify(folder)
    assert result.status == "READY", result.reason
    assert result.manifest["license_review_status"] == "SYNTHETIC"
    assert smoke.build(tmp_path) == folder  # rebuildable in place
