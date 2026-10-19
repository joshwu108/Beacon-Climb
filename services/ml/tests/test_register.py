"""BC-0205: registry row mapping (no database needed)."""

from beacon_ml.registry import Verified

from app import register

MANIFEST = {
    "model_version": "model-a-gbm-x",
    "family": "model-a",
    "kind": "sklearn-hgb",
    "artifact_digest": "a" * 64,
    "input_schema_version": "model-a-features-v1",
    "training_manifest_id": "model-a-000000000000",
    "training_run_id": "run",
    "metrics_ref": "docs/x",
    "license_review_status": "LOCAL_RESEARCH_ONLY",
}


def test_ready_version_is_activated_when_asked() -> None:
    row = register.row_for(Verified(MANIFEST, "READY", None, None), activate=True)
    assert row["status"] == "READY" and row["active"] is True and row["reason"] is None


def test_rejected_version_is_never_activated() -> None:
    row = register.row_for(Verified(MANIFEST, "REJECTED", "sha256 mismatch", None), activate=True)
    assert row["active"] is False and row["reason"] == "sha256 mismatch"


def test_missing_database_url_is_a_usage_error(monkeypatch, tmp_path) -> None:
    monkeypatch.delenv("BEACON_ML_DB_URL", raising=False)
    assert register.main(["--dir", str(tmp_path)]) == 2


def test_unreadable_manifest_is_not_registered(monkeypatch, tmp_path) -> None:
    monkeypatch.setenv("BEACON_ML_DB_URL", "postgresql://unused")
    assert register.main(["--dir", str(tmp_path)]) == 1
