"""Model registry packaging and load-time validation (BC-0205).

``package`` copies a trained Model A artifact into ``data/models/registry/<model_version>/``
(gitignored) and writes ``manifest.json`` (contracts/ml/model-manifest.v1.schema.json) with a
fixture: synthetic rows and the grades this exact artifact predicts for them.

``verify`` is what the inference service runs before serving: it recomputes every file sha256
and the artifact digest, refuses to deserialize anything that does not match, loads the model,
and re-predicts the fixture. Status is READY only if every prediction is within tolerance;
otherwise REJECTED with a reason, and the caller keeps the previously active version.

The sklearn artifact is a pickle. Pickles execute code on load, so ``verify`` unpickles only
after the digest check, and only artifacts produced locally by ``package`` are ever registered.
"""

from __future__ import annotations

import hashlib
import json
import pickle
import shutil
from collections.abc import Callable
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path

import numpy as np

from beacon_ml.features import model_a as fa

REPO_ROOT = Path(__file__).resolve().parents[2]
REGISTRY_ROOT = REPO_ROOT / "data" / "models" / "registry"
FIXTURE_FILE = Path(fa.__file__).parent / "fixtures" / "model_a_features.v1.json"
TOLERANCE = 1e-6
KIND_FILES = {
    "sklearn-hgb": ("model.pkl",),
    "torch-deepsets": ("state_dict.pt", "vocab.json", "candidate.json"),
}

Predictor = Callable[[list[dict]], np.ndarray]


@dataclass(frozen=True)
class Verified:
    manifest: dict
    status: str  # READY | REJECTED
    reason: str | None
    predict: Predictor | None
    embed: Predictor | None = None  # [n, 32] route embeddings; None when the kind has none


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def artifact_digest(files: list[dict]) -> str:
    lines = [f"{f['path']}:{f['sha256']}" for f in sorted(files, key=lambda f: f["path"])]
    return hashlib.sha256("\n".join(lines).encode()).hexdigest()


def fixture_rows() -> list[dict]:
    cases = json.loads(FIXTURE_FILE.read_text())["cases"]
    return [{"angle_deg": c["angle_deg"], "holds": c["holds"]} for c in cases]


def _sklearn_model(folder: Path) -> tuple[Predictor, None]:
    from beacon_ml.train.data import to_split

    model = pickle.loads((folder / "model.pkl").read_bytes())

    def predict(rows: list[dict]) -> np.ndarray:
        return np.asarray(model.predict(to_split("inference", rows)), dtype=np.float64)

    return predict, None


def _torch_model(folder: Path) -> tuple[Predictor, Predictor]:
    import torch

    from beacon_ml.train import deepsets

    candidate = json.loads((folder / "candidate.json").read_text())
    vocab = deepsets.TokenVocab(json.loads((folder / "vocab.json").read_text()))
    model = deepsets.DeepSets(
        candidate["vocab_size"], hidden=candidate["hidden"], dropout=candidate.get("dropout", 0.0)
    )
    model.load_state_dict(torch.load(folder / "state_dict.pt", weights_only=True))
    model.eval()

    def predict(rows: list[dict]) -> np.ndarray:
        return deepsets.predict(model, deepsets.encode(rows, vocab, candidate["max_holds"]))

    def embed(rows: list[dict]) -> np.ndarray:
        enc = deepsets.encode(rows, vocab, candidate["max_holds"])
        with torch.no_grad():
            out = model.embed(enc.hold_ids, enc.numeric, enc.mask, enc.angle)
        return out.numpy().astype(np.float64)

    return predict, embed


# kind -> loader returning (predict, embed or None); called only after the digest check.
LOADERS = {"sklearn-hgb": _sklearn_model, "torch-deepsets": _torch_model}


def package(
    *,
    kind: str,
    source_dir: Path,
    model_version: str,
    training_manifest_id: str,
    training_run_id: str,
    metrics_ref: str,
    registry_root: Path = REGISTRY_ROOT,
    now: datetime | None = None,
    license_review_status: str = "LOCAL_RESEARCH_ONLY",
) -> Path:
    folder = Path(registry_root) / model_version
    if folder.exists():
        raise FileExistsError(f"{folder} exists; model versions are immutable")
    folder.mkdir(parents=True)
    for name in KIND_FILES[kind]:
        shutil.copyfile(Path(source_dir) / name, folder / name)
    files = [{"path": n, "sha256": sha256(folder / n)} for n in KIND_FILES[kind]]
    rows = fixture_rows()
    expected = LOADERS[kind](folder)[0](rows)
    manifest = {
        "schema_version": 1,
        "model_version": model_version,
        "family": "model-a",
        "kind": kind,
        "files": files,
        "artifact_digest": artifact_digest(files),
        "input_schema_version": fa.FEATURE_VERSION,
        "training_manifest_id": training_manifest_id,
        "training_run_id": training_run_id,
        "metrics_ref": metrics_ref,
        "license_review_status": license_review_status,
        "created_at": (now or datetime.now(UTC)).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "fixture": {
            "rows": rows,
            "expected_grade": [float(x) for x in expected],
            "tolerance": TOLERANCE,
        },
    }
    (folder / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    return folder


def verify(folder: Path) -> Verified:
    folder = Path(folder)
    try:
        manifest = json.loads((folder / "manifest.json").read_text())
    except (OSError, json.JSONDecodeError) as exc:
        return Verified({}, "REJECTED", f"manifest unreadable: {exc}", None)
    if manifest.get("input_schema_version") != fa.FEATURE_VERSION:
        return Verified(manifest, "REJECTED", "input_schema_version mismatch", None)
    kind = manifest.get("kind")
    if kind not in LOADERS or sorted(f["path"] for f in manifest["files"]) != sorted(
        KIND_FILES[kind]
    ):
        return Verified(manifest, "REJECTED", f"unexpected kind or file set for {kind!r}", None)
    for entry in manifest["files"]:
        path = folder / entry["path"]
        if not path.is_file() or sha256(path) != entry["sha256"]:
            return Verified(manifest, "REJECTED", f"sha256 mismatch for {entry['path']}", None)
    if artifact_digest(manifest["files"]) != manifest["artifact_digest"]:
        return Verified(manifest, "REJECTED", "artifact_digest mismatch", None)
    try:
        predict, embed = LOADERS[kind](folder)
        got = predict(manifest["fixture"]["rows"])
    except Exception as exc:  # noqa: BLE001 - any load/inference failure rejects the version
        return Verified(manifest, "REJECTED", f"load or fixture inference failed: {exc}", None)
    expected = np.asarray(manifest["fixture"]["expected_grade"], dtype=np.float64)
    if got.shape != expected.shape or not np.all(
        np.abs(got - expected) <= manifest["fixture"]["tolerance"]
    ):
        return Verified(manifest, "REJECTED", "fixture predictions differ from the manifest", None)
    return Verified(manifest, "READY", None, predict, embed)


def main(argv: list[str] | None = None) -> int:
    import argparse
    import sys

    parser = argparse.ArgumentParser(description="Package a trained Model A artifact (BC-0205)")
    parser.add_argument("--kind", choices=sorted(KIND_FILES), required=True)
    parser.add_argument("--source-dir", type=Path, required=True)
    parser.add_argument("--model-version", required=True)
    parser.add_argument("--training-manifest-id", required=True)
    parser.add_argument("--training-run-id", required=True)
    parser.add_argument("--metrics-ref", required=True)
    args = parser.parse_args(argv)
    folder = package(
        kind=args.kind,
        source_dir=args.source_dir,
        model_version=args.model_version,
        training_manifest_id=args.training_manifest_id,
        training_run_id=args.training_run_id,
        metrics_ref=args.metrics_ref,
    )
    result = verify(folder)
    print(json.dumps({"folder": str(folder), "status": result.status, "reason": result.reason}))
    sys.stdout.flush()
    return 0 if result.status == "READY" else 1


if __name__ == "__main__":
    raise SystemExit(main())
