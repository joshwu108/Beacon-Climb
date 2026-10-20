"""Remote Deep Sets validation-stage training on Modal (BC-0213, ``make train-remote``).

    python -I -m beacon_ml.remote.modal_train --config configs/model_a_deepsets.json \
        --gpu T4 [--rights-signed]

What runs remotely is exactly ``run_deepsets.stage_val`` (same code, configs and seeds as a
local run) on a Modal GPU; the report and the frozen checkpoint come back to the same gitignored
places as a local run (``reports/model-a/<run>/``, ``data/models/model-a-deepsets/<run>/``), with
``environment: modal:<gpu>``. Test evaluation stays local: the sealed test split is never
uploaded (only train and val files go to the Modal Volume).

Data-rights gate (ADR-0007, docs/data-source-audit.md section 7): a non-synthetic dataset is
refused before any upload unless ``--rights-signed`` is passed AND the audit carries the owner's
signed acceptance line. Dataset rows hold no personal columns (BC-0103 never reads them).
Cost: Modal bills per second; the report records GPU type and wall time, and an estimate only if
``usd_per_hour`` is set in the config (no invented prices). Credentials come from Modal's own
config (``modal setup`` or MODAL_TOKEN_ID / MODAL_TOKEN_SECRET); they are never read or logged
here. CI never calls Modal.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import time
from datetime import UTC, datetime
from pathlib import Path

ML_ROOT = Path(__file__).resolve().parents[2]
REPO_ROOT = ML_ROOT.parent
AUDIT = REPO_ROOT / "docs" / "data-source-audit.md"
DATA_ROOT = REPO_ROOT / "data"
ACCESS_FILE = ML_ROOT / "datasets" / "model_a" / "access.py"
SIGNED_LINE = re.compile(r"^\[x\] I accept use \(a\) local research import", re.MULTILINE)
UPLOAD_SPLITS = ("train", "val")  # never "test": the sealed split stays on this machine
VOLUME = "beacon-model-a-data"
APP_NAME = "beacon-model-a-train"
REMOTE_DATA = "/data"
REMOTE_ACCESS = "/root/beacon_access/access.py"
PINNED = ("numpy==2.5.3", "scikit-learn==1.9.1", "torch==2.14.1")  # same versions as ml/uv.lock
TIMEOUT_S = 3 * 60 * 60


class Refused(Exception):
    pass


def check_gate(manifest: dict, rights_signed: bool, audit: Path = AUDIT) -> None:
    """Raise Refused unless this dataset may be uploaded to Modal."""
    if manifest.get("license_review_status") == "SYNTHETIC":
        return
    if not rights_signed:
        raise Refused("non-synthetic dataset: pass --rights-signed after reading the audit")
    if not audit.is_file() or not SIGNED_LINE.search(audit.read_text(encoding="utf-8")):
        raise Refused(f"no signed local-research acceptance line in {audit}")


def upload_plan(manifest: dict, data_root: Path = DATA_ROOT) -> list[tuple[Path, str]]:
    """(local file, remote path) for the train and val files only, hash-checked first."""
    import hashlib

    folder = Path(data_root) / "datasets" / manifest["dataset_id"]
    plan = []
    for split in UPLOAD_SPLITS:
        local = folder / f"{split}.jsonl"
        digest = hashlib.sha256(local.read_bytes()).hexdigest()
        if digest != manifest["files_sha256"][split]:
            raise Refused(f"{local} does not match the frozen manifest")
        plan.append((local, f"/datasets/{manifest['dataset_id']}/{split}.jsonl"))
    return plan


def remote_manifest(manifest: dict) -> dict:
    """The manifest as the remote side sees it: no test hash, so test can never be opened."""
    files = {k: v for k, v in manifest["files_sha256"].items() if k in UPLOAD_SPLITS}
    return {**manifest, "files_sha256": files, "sealed_split": "test"}


def cost_estimate(config: dict, wall_seconds: float) -> dict:
    rate = config.get("usd_per_hour")
    return {
        "usd_per_hour": rate,
        "estimated_usd": round(rate * wall_seconds / 3600, 4) if rate else None,
        "note": "Modal bills per second; check the Modal dashboard for the actual charge",
    }


def build_app(gpu: str):
    import modal

    image = (
        modal.Image.debian_slim(python_version="3.12")
        .uv_pip_install(*PINNED)
        .add_local_file(str(ACCESS_FILE), REMOTE_ACCESS)
        .add_local_python_source("beacon_ml")
    )
    app = modal.App(APP_NAME, image=image)
    volume = modal.Volume.from_name(VOLUME, create_if_missing=True)

    @app.function(gpu=gpu, volumes={REMOTE_DATA: volume}, timeout=TIMEOUT_S, serialized=True)
    def train_val(config: dict, manifest: dict, run_id: str) -> dict:
        import base64
        from pathlib import Path as P

        import torch

        from beacon_ml.train import data as data_module
        from beacon_ml.train import run, run_deepsets

        data_module.ACCESS_PATH = P(REMOTE_ACCESS)
        config = {**config, "device": "cuda" if torch.cuda.is_available() else "cpu"}
        models = P("/tmp/models")
        body = run_deepsets.stage_val(
            config,
            manifest,
            run.grade_names(manifest),
            run_id,
            data_root=P(REMOTE_DATA),
            models_dir=models,
        )
        files = {
            p.name: base64.b64encode(p.read_bytes()).decode() for p in (models / run_id).iterdir()
        }
        return {
            "body": body,
            "files": files,
            "device": config["device"],
            "torch": torch.__version__,
            "cuda": torch.cuda.get_device_name(0) if torch.cuda.is_available() else None,
        }

    return app, volume, train_val


def main(argv: list[str] | None = None) -> int:
    import base64

    parser = argparse.ArgumentParser(description="Remote Deep Sets val-stage training (BC-0213)")
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--gpu", default="T4")
    parser.add_argument("--rights-signed", action="store_true")
    args = parser.parse_args(argv)

    from beacon_ml.train import data
    from beacon_ml.train.run import REPORTS, _git
    from beacon_ml.train.run_deepsets import MODELS

    config_path = args.config if args.config.is_absolute() else ML_ROOT / args.config
    config_bytes = config_path.read_bytes()
    config = json.loads(config_bytes)
    manifest = data.access.read_manifest(ML_ROOT / config["manifest"])
    try:
        check_gate(manifest, args.rights_signed)
        plan = upload_plan(manifest)
    except Refused as exc:
        print(f"REFUSED (nothing uploaded): {exc}", file=sys.stderr)
        return 2

    import modal

    started = datetime.now(UTC)
    git_at_start = _git()
    run_id = f"{started.strftime('%Y%m%dT%H%M%SZ')}-deepsets-val-modal"
    app, volume, train_val = build_app(args.gpu)
    t0 = time.perf_counter()
    with volume.batch_upload(force=True) as batch:
        for local, remote in plan:
            batch.put_file(str(local), remote)
    upload_seconds = round(time.perf_counter() - t0, 1)
    with modal.enable_output(), app.run():
        result = train_val.remote(config, remote_manifest(manifest), run_id)
    wall = round(time.perf_counter() - t0, 1)

    model_dir = MODELS / run_id
    model_dir.mkdir(parents=True, exist_ok=True)
    for name, encoded in result["files"].items():
        (model_dir / name).write_bytes(base64.b64decode(encoded))
    out_dir = REPORTS / run_id
    out_dir.mkdir(parents=True, exist_ok=True)
    import hashlib

    report = {
        "run_id": run_id,
        "ticket": "BC-0213 (BC-0204 val stage)",
        "dataset_id": manifest["dataset_id"],
        "dataset_files_sha256": remote_manifest(manifest)["files_sha256"],
        "uploaded_splits": list(UPLOAD_SPLITS),
        "config": config,
        "config_sha256": hashlib.sha256(config_bytes).hexdigest(),
        "git": git_at_start,
        "environment": f"modal:{args.gpu}",
        "hardware": {
            "gpu_requested": args.gpu,
            "gpu_reported": result["cuda"],
            "device": result["device"],
            "torch": result["torch"],
        },
        "started_at": started.strftime("%Y-%m-%dT%H:%M:%SZ"),
        "upload_seconds": upload_seconds,
        "wall_seconds": wall,
        "cost": cost_estimate(config, wall),
        "units": "Aurora difficulty_numeric (1 unit = one Font sub-grade)",
        **result["body"],
    }
    (out_dir / "metrics.json").write_text(json.dumps(report, indent=2) + "\n")
    print(
        json.dumps(
            {
                "run_id": run_id,
                "report": str(out_dir / "metrics.json"),
                "candidate": str(model_dir / "candidate.json"),
            }
        )
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
