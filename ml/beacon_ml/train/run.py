"""Model A baselines entry point (BC-0203).

    python -I -m beacon_ml.train.run --config configs/model_a_baselines.json --stage val
    python -I -m beacon_ml.train.run --config configs/model_a_baselines.json --stage test \
        --selection <reports/model-a/<run>/selection.json>

``val``  fits every baseline and the gradient-boosting grid on train, scores them on val, and
         freezes the best grid point per feature set into ``selection.json``. Test is not read.
``test`` refits exactly the frozen candidates on train, unseals test ONCE (logged by
         access.py) and reports test metrics with climb-level bootstrap intervals and paired
         differences. Reports go to gitignored ``reports/model-a/<run id>/``; the best model is
         pickled to gitignored ``data/models/model-a/<run id>/``.
"""

from __future__ import annotations

import argparse
import hashlib
import itertools
import json
import os
import pickle
import platform
import subprocess
import sys
import time
from datetime import UTC, datetime
from importlib import metadata
from pathlib import Path

import numpy as np

from . import data
from .baselines import AngleHoldsMedian, AngleMedian, Gbm, GlobalMedian
from .metrics import evaluate, paired_mae_difference, to_v

ML_ROOT = Path(__file__).resolve().parents[2]
REPO_ROOT = ML_ROOT.parent
REPORTS = REPO_ROOT / "reports" / "model-a"
MODELS = REPO_ROOT / "data" / "models" / "model-a"


def _git() -> dict:
    def run(*args):
        return subprocess.run(
            ["git", *args], cwd=REPO_ROOT, capture_output=True, text=True, check=False
        ).stdout.strip()

    return {
        "commit": run("rev-parse", "HEAD"),
        "ml_dirty": bool(run("status", "--porcelain", "--", "ml")),
    }


def _hardware() -> dict:
    chip = (
        subprocess.run(
            ["sysctl", "-n", "machdep.cpu.brand_string"],
            capture_output=True,
            text=True,
            check=False,
        ).stdout.strip()
        or platform.processor()
    )
    return {
        "chip": chip,
        "cpus": os.cpu_count(),
        "os": platform.platform(),
        "python": platform.python_version(),
        "numpy": metadata.version("numpy"),
        "scikit-learn": metadata.version("scikit-learn"),
    }


def grade_names(manifest: dict) -> dict[int, str]:
    names = {}
    for split in manifest["splits"].values():
        for item in split["by_grade"]:
            names[int(item["difficulty"])] = item["name"]
    return names


def candidates(config: dict) -> list:
    grid = config["gbm_grid"]
    gbms = [
        Gbm(use_holds=h, learning_rate=lr, max_leaf_nodes=leaves, max_iter=it, seed=config["seed"])
        for h, lr, leaves, it in itertools.product(
            grid["use_holds"], grid["learning_rate"], grid["max_leaf_nodes"], grid["max_iter"]
        )
    ]
    return [GlobalMedian(), AngleMedian(), AngleHoldsMedian(), *gbms]


def slices(split: data.Split, names: dict[int, str], config: dict) -> dict[str, np.ndarray]:
    out = {"all": np.ones(len(split), dtype=bool)}
    for minimum in config["slices"]["ascents_min"]:
        out[f"ascents>={minimum}"] = split.ascents >= minimum
    for angle in sorted(set(split.angle.tolist())):
        out[f"angle={angle}"] = split.angle == angle
    v_true = to_v(split.grade, names)
    for v in sorted(set(v_true.tolist())):
        out[f"true_V{v}"] = v_true == v
    return out


def score(pred, split, names, config) -> dict:
    res = {}
    for label, mask in slices(split, names, config).items():
        if mask.any():
            res[label] = evaluate(
                pred[mask],
                split.grade[mask],
                split.route[mask],
                names,
                resamples=config["bootstrap_resamples"],
                seed=config["seed"],
            )
    return res


def fit_all(models, train):
    timings = {}
    for model in models:
        start = time.perf_counter()
        model.fit(train)
        timings[model.name] = round(time.perf_counter() - start, 2)
    return timings


def stage_val(config, manifest, names, out_dir, data_root=None) -> dict:
    train = data.load(manifest, "train", data_root=data_root)
    val = data.load(manifest, "val", data_root=data_root)
    models = candidates(config)
    timings = fit_all(models, train)
    results = {m.name: score(m.predict(val), val, names, config) for m in models}
    selection = {"baselines": ["median-global", "median-by-angle", "median-by-angle-and-holds"]}
    for use_holds in config["gbm_grid"]["use_holds"]:
        pool = [m for m in models if isinstance(m, Gbm) and m.use_holds == use_holds]
        best = min(pool, key=lambda m: results[m.name]["all"]["mae"])
        key = "gbm_geo_holds" if use_holds else "gbm_geo"
        selection[key] = {
            "use_holds": best.use_holds,
            "learning_rate": best.learning_rate,
            "max_leaf_nodes": best.max_leaf_nodes,
            "max_iter": best.max_iter,
            "val_mae": results[best.name]["all"]["mae"],
        }
    (out_dir / "selection.json").write_text(json.dumps(selection, indent=2) + "\n")
    return {
        "split": "val",
        "n_train": len(train),
        "n_eval": len(val),
        "fit_seconds": timings,
        "results": results,
        "selection": selection,
    }


def stage_test(
    config, manifest, names, out_dir, selection_path, run_id, data_root=None, models_dir=MODELS
) -> dict:
    selection = json.loads(Path(selection_path).read_text())
    models = [GlobalMedian(), AngleMedian(), AngleHoldsMedian()]
    for key in ("gbm_geo", "gbm_geo_holds"):
        if key in selection:
            spec = {k: v for k, v in selection[key].items() if k != "val_mae"}
            models.append(Gbm(seed=config["seed"], **spec))
    train = data.load(manifest, "train", data_root=data_root)
    timings = fit_all(models, train)
    purpose = f"BC-0203 final test of frozen candidates {[m.name for m in models]} run {run_id}"
    test = data.load(manifest, "test", data_root=data_root, unseal_purpose=purpose)
    preds = {m.name: m.predict(test) for m in models}
    results = {name: score(p, test, names, config) for name, p in preds.items()}
    best = min(models, key=lambda m: results[m.name]["all"]["mae"])
    best_lookup = min(models[:3], key=lambda m: results[m.name]["all"]["mae"])
    paired = {}
    for model in models:
        if model is not best_lookup:
            paired[f"{model.name} vs {best_lookup.name}"] = paired_mae_difference(
                preds[model.name],
                preds[best_lookup.name],
                test.grade,
                test.route,
                resamples=config["bootstrap_resamples"],
                seed=config["seed"],
            )
    gbms = [m for m in models if isinstance(m, Gbm)]
    if len(gbms) == 2:
        a, b = sorted(gbms, key=lambda m: m.use_holds, reverse=True)
        paired[f"{a.name} vs {b.name}"] = paired_mae_difference(
            preds[a.name],
            preds[b.name],
            test.grade,
            test.route,
            resamples=config["bootstrap_resamples"],
            seed=config["seed"],
        )
    model_dir = Path(models_dir) / run_id
    model_dir.mkdir(parents=True, exist_ok=True)
    artifact = model_dir / "model.pkl"
    artifact.write_bytes(pickle.dumps(best))
    return {
        "split": "test",
        "n_train": len(train),
        "n_eval": len(test),
        "fit_seconds": timings,
        "selection": selection,
        "results": results,
        "paired_differences": paired,
        "best": best.name,
        "artifact": {
            "path": artifact.name,
            "sha256": hashlib.sha256(artifact.read_bytes()).hexdigest(),
        },
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Model A baselines (BC-0203)")
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--stage", choices=("val", "test"), required=True)
    parser.add_argument("--selection", type=Path)
    args = parser.parse_args(argv)
    if args.stage == "test" and not args.selection:
        parser.error("--stage test needs --selection from a val run")

    config_bytes = (
        ML_ROOT / args.config if not args.config.is_absolute() else args.config
    ).read_bytes()
    config = json.loads(config_bytes)
    manifest = data.access.read_manifest(ML_ROOT / config["manifest"])
    names = grade_names(manifest)
    started = datetime.now(UTC)
    git_at_start = _git()  # the code that actually ran, not HEAD at the end
    run_id = f"{started.strftime('%Y%m%dT%H%M%SZ')}-{args.stage}"
    out_dir = REPORTS / run_id
    out_dir.mkdir(parents=True, exist_ok=True)
    t0 = time.perf_counter()
    if args.stage == "val":
        body = stage_val(config, manifest, names, out_dir)
    else:
        body = stage_test(config, manifest, names, out_dir, args.selection, run_id)
    report = {
        "run_id": run_id,
        "ticket": "BC-0203",
        "dataset_id": manifest["dataset_id"],
        "dataset_files_sha256": manifest["files_sha256"],
        "feature_version": data.fa.FEATURE_VERSION,
        "config": config,
        "config_sha256": hashlib.sha256(config_bytes).hexdigest(),
        "git": git_at_start,
        "environment": "local",
        "hardware": _hardware(),
        "started_at": started.strftime("%Y-%m-%dT%H:%M:%SZ"),
        "wall_seconds": round(time.perf_counter() - t0, 1),
        "units": "Aurora difficulty_numeric (1 unit = one Font sub-grade)",
        **body,
    }
    (out_dir / "metrics.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({"run_id": run_id, "report": str(out_dir / "metrics.json")}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
