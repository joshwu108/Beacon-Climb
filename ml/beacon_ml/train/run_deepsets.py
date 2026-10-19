"""Deep Sets candidate entry point (BC-0204).

    python -I -m beacon_ml.train.run_deepsets --config configs/model_a_deepsets.json --stage val
    python -I -m beacon_ml.train.run_deepsets --config configs/model_a_deepsets.json --stage test \
        --candidate <data/models/model-a-deepsets/<val run>/candidate.json> \
        --baseline-selection <reports/model-a/<baseline val run>/selection.json>

``val``  trains with early stopping on val, freezes the best checkpoint (state dict + vocab,
         sha256 recorded) and reports val metrics. Test is not read.
``test`` loads the frozen checkpoint (hash-checked), refits the frozen gradient-boosting
         selection for a paired comparison, unseals test once and reports both. The served
         artifact is the hash-checked state dict + vocab + candidate.json (TorchScript is
         deprecated in torch 2.14, so it is not used).
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
import time
from datetime import UTC, datetime
from pathlib import Path

import numpy as np
import torch

from . import data, deepsets
from .baselines import Gbm
from .metrics import paired_mae_difference
from .run import ML_ROOT, REPO_ROOT, REPORTS, _git, _hardware, grade_names, score

MODELS = REPO_ROOT / "data" / "models" / "model-a-deepsets"


def _sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def stage_val(config, manifest, names, run_id, data_root=None, models_dir=MODELS) -> dict:
    train_rows = list(data.access.load_split(manifest, "train", **_root(data_root)))
    val_rows = list(data.access.load_split(manifest, "val", **_root(data_root)))
    vocab = deepsets.TokenVocab.fit(train_rows)
    enc_train = deepsets.encode(train_rows, vocab, config["max_holds"])
    enc_val = deepsets.encode(val_rows, vocab, config["max_holds"])
    start = time.perf_counter()
    model, history = deepsets.train(
        enc_train,
        enc_val,
        vocab.size,
        seed=config["seed"],
        epochs=config["epochs"],
        patience=config["patience"],
        batch=config["batch"],
        lr=config["lr"],
        weight_decay=config["weight_decay"],
        hidden=config["hidden"],
    )
    seconds = round(time.perf_counter() - start, 1)
    val_split = data.to_split("val", val_rows)
    results = {"deepsets": score(deepsets.predict(model, enc_val), val_split, names, config)}

    out = Path(models_dir) / run_id
    out.mkdir(parents=True, exist_ok=True)
    torch.save(model.state_dict(), out / "state_dict.pt")
    (out / "vocab.json").write_text(json.dumps(sorted(vocab.index, key=vocab.index.get)))
    candidate = {
        "run_id": run_id,
        "hidden": config["hidden"],
        "max_holds": config["max_holds"],
        "vocab_size": vocab.size,
        "best_epoch": min(history, key=lambda h: h["val_mae"])["epoch"],
        "state_dict_sha256": _sha(out / "state_dict.pt"),
        "vocab_sha256": _sha(out / "vocab.json"),
        "feature_version": data.fa.FEATURE_VERSION,
    }
    (out / "candidate.json").write_text(json.dumps(candidate, indent=2) + "\n")
    return {
        "split": "val",
        "n_train": len(train_rows),
        "n_eval": len(val_rows),
        "fit_seconds": {"deepsets": seconds},
        "history": history,
        "candidate": candidate,
        "results": results,
    }


def _root(data_root):
    return {"data_root": data_root} if data_root is not None else {}


def _load_candidate(candidate_path: Path):
    folder = candidate_path.parent
    candidate = json.loads(candidate_path.read_text())
    if _sha(folder / "state_dict.pt") != candidate["state_dict_sha256"]:
        raise SystemExit("state_dict.pt does not match candidate.json")
    if _sha(folder / "vocab.json") != candidate["vocab_sha256"]:
        raise SystemExit("vocab.json does not match candidate.json")
    vocab = deepsets.TokenVocab(json.loads((folder / "vocab.json").read_text()))
    model = deepsets.DeepSets(candidate["vocab_size"], hidden=candidate["hidden"])
    model.load_state_dict(torch.load(folder / "state_dict.pt", weights_only=True))
    model.eval()
    return candidate, vocab, model, folder


def stage_test(
    config, manifest, names, run_id, candidate_path, selection_path, data_root=None
) -> dict:
    candidate, vocab, model, _ = _load_candidate(Path(candidate_path))
    selection = json.loads(Path(selection_path).read_text())
    spec = {k: v for k, v in selection["gbm_geo_holds"].items() if k != "val_mae"}
    gbm = Gbm(seed=config["seed"], **spec)  # same seed as the baseline config
    train = data.load(manifest, "train", data_root=data_root)
    gbm.fit(train)
    purpose = (
        f"BC-0204 final test of frozen deepsets {candidate['run_id']} vs frozen "
        f"{gbm.name}, run {run_id}"
    )
    test_rows = list(
        data.access.load_split(manifest, "test", unseal_purpose=purpose, **_root(data_root))
    )
    test = data.to_split("test", test_rows)
    pred_ds = deepsets.predict(model, deepsets.encode(test_rows, vocab, candidate["max_holds"]))
    pred_gbm = gbm.predict(test)
    results = {
        "deepsets": score(pred_ds, test, names, config),
        gbm.name: score(pred_gbm, test, names, config),
    }
    paired = {
        f"deepsets vs {gbm.name}": paired_mae_difference(
            pred_ds,
            pred_gbm,
            test.grade,
            test.route,
            resamples=config["bootstrap_resamples"],
            seed=config["seed"],
        )
    }
    return {
        "split": "test",
        "n_eval": len(test_rows),
        "candidate": candidate,
        "results": results,
        "paired_differences": paired,
        "artifact": {
            "path": "state_dict.pt + vocab.json + candidate.json",
            "state_dict_sha256": candidate["state_dict_sha256"],
            "vocab_sha256": candidate["vocab_sha256"],
        },
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Model A Deep Sets candidate (BC-0204)")
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--stage", choices=("val", "test"), required=True)
    parser.add_argument("--candidate", type=Path)
    parser.add_argument("--baseline-selection", type=Path)
    args = parser.parse_args(argv)
    if args.stage == "test" and not (args.candidate and args.baseline_selection):
        parser.error("--stage test needs --candidate and --baseline-selection")
    config_path = args.config if args.config.is_absolute() else ML_ROOT / args.config
    config_bytes = config_path.read_bytes()
    config = json.loads(config_bytes)
    torch.set_num_threads(config["threads"])
    manifest = data.access.read_manifest(ML_ROOT / config["manifest"])
    names = grade_names(manifest)
    started = datetime.now(UTC)
    run_id = f"{started.strftime('%Y%m%dT%H%M%SZ')}-deepsets-{args.stage}"
    out_dir = REPORTS / run_id
    out_dir.mkdir(parents=True, exist_ok=True)
    t0 = time.perf_counter()
    if args.stage == "val":
        body = stage_val(config, manifest, names, run_id)
    else:
        body = stage_test(config, manifest, names, run_id, args.candidate, args.baseline_selection)
    report = {
        "run_id": run_id,
        "ticket": "BC-0204",
        "dataset_id": manifest["dataset_id"],
        "dataset_files_sha256": manifest["files_sha256"],
        "config": config,
        "config_sha256": hashlib.sha256(config_bytes).hexdigest(),
        "git": _git(),
        "environment": "local",
        "hardware": {
            **_hardware(),
            "torch": torch.__version__,
            "torch_threads": torch.get_num_threads(),
        },
        "started_at": started.strftime("%Y-%m-%dT%H:%M:%SZ"),
        "wall_seconds": round(time.perf_counter() - t0, 1),
        "units": "Aurora difficulty_numeric (1 unit = one Font sub-grade)",
        **body,
    }
    (out_dir / "metrics.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({"run_id": run_id, "report": str(out_dir / "metrics.json")}))
    return 0


if __name__ == "__main__":
    np.seterr(all="ignore")
    sys.exit(main())
