"""BC-0206: inference endpoints, readiness and rejection semantics (synthetic models only)."""

from __future__ import annotations

import json

from fastapi.testclient import TestClient

from app.main import MAX_ROUTES, app

ROUTE = {
    "angle_deg": 40,
    "holds": [
        {"id": "a", "x": 0, "y": 4, "role": "START"},
        {"id": "b", "x": 60, "y": 90, "role": "HAND"},
        {"id": "c", "x": 100, "y": 150, "role": "FINISH"},
    ],
}


def _client(monkeypatch, folder) -> TestClient:
    if folder is None:
        monkeypatch.delenv("BEACON_ML_MODEL_DIR", raising=False)
    else:
        monkeypatch.setenv("BEACON_ML_MODEL_DIR", str(folder))
    return TestClient(app)


def test_not_configured_is_not_ready_and_refuses_predictions(monkeypatch):
    with _client(monkeypatch, None) as c:
        assert c.get("/health/ready").status_code == 503
        assert c.get("/health/live").status_code == 200
        assert c.get("/v1/models").json()["status"] == "NOT_CONFIGURED"
        r = c.post("/v1/grade-predictions", json={"routes": [ROUTE]})
        assert r.status_code == 503 and r.json()["error"]["code"] == "MODEL_NOT_READY"


def test_gbm_version_serves_predictions_but_no_embeddings(monkeypatch, registry_root):
    with _client(monkeypatch, registry_root / "model-a-gbm-t") as c:
        assert c.get("/health/ready").status_code == 200
        info = c.get("/v1/models").json()
        assert info["status"] == "READY" and info["has_embeddings"] is False
        r = c.post("/v1/grade-predictions", json={"routes": [ROUTE, ROUTE]})
        body = r.json()
        assert r.status_code == 200 and body["model_version"] == "model-a-gbm-t"
        assert body["input_schema_version"] == "model-a-features-v1"
        assert len(body["predictions"]) == 2
        assert body["predictions"][0] == body["predictions"][1]
        e = c.post("/v1/route-embeddings", json={"routes": [ROUTE]})
        assert e.status_code == 409 and e.json()["error"]["code"] == "MODEL_HAS_NO_EMBEDDINGS"


def test_deepsets_version_serves_32_dimensional_embeddings(monkeypatch, registry_root):
    with _client(monkeypatch, registry_root / "model-a-ds-t") as c:
        e = c.post("/v1/route-embeddings", json={"routes": [ROUTE]})
        assert e.status_code == 200
        assert e.json()["dimension"] == 32 and len(e.json()["embeddings"][0]) == 32


def test_tampered_artifact_never_serves(monkeypatch, registry_root, tmp_path):
    import shutil

    folder = tmp_path / "copy"
    shutil.copytree(registry_root / "model-a-gbm-t", folder)
    (folder / "model.pkl").write_bytes(b"tampered")
    with _client(monkeypatch, folder) as c:
        info = c.get("/v1/models").json()
        assert info["status"] == "REJECTED" and "sha256" in info["reason"]
        assert c.post("/v1/grade-predictions", json={"routes": [ROUTE]}).status_code == 503


def test_request_validation_bounds(monkeypatch, registry_root):
    with _client(monkeypatch, registry_root / "model-a-gbm-t") as c:
        too_many = {"routes": [ROUTE] * (MAX_ROUTES + 1)}
        assert c.post("/v1/grade-predictions", json=too_many).status_code == 422
        bad_role = json.loads(json.dumps(ROUTE))
        bad_role["holds"][0]["role"] = "MATCH"
        assert c.post("/v1/grade-predictions", json={"routes": [bad_role]}).status_code == 422
        extra = {**ROUTE, "notes": "x"}
        assert c.post("/v1/grade-predictions", json={"routes": [extra]}).status_code == 422
        assert c.post("/v1/grade-predictions", json={"routes": []}).status_code == 422


def test_committed_contract_matches_the_app():
    from pathlib import Path

    committed = Path(__file__).resolve().parents[3] / "contracts" / "ml-inference.openapi.json"
    assert json.loads(committed.read_text()) == app.openapi(), (
        "contracts/ml-inference.openapi.json is stale; regenerate it from app.openapi()"
    )
