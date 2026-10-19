"""Synthetic smoke model for clean clones and CI (BC-0212). SYNTHETIC: no Kilter data.

    python -I -m beacon_ml.smoke [--registry-root DIR]

Generates random climbs on a made-up grid (grade = height + angle + noise), trains a tiny
gradient-boosting model and packages it as ``model-a-synthetic-smoke`` with
``license_review_status: SYNTHETIC``. It proves the registry, the inference service and the
compose wiring work; its predictions mean nothing about real climbs.
"""

from __future__ import annotations

import argparse
import json
import pickle
import random
import shutil
import sys
import tempfile
from pathlib import Path

from beacon_ml import registry
from beacon_ml.train.baselines import Gbm
from beacon_ml.train.data import to_split

VERSION = "model-a-synthetic-smoke"
SEED = 20261019


def synthetic_rows(n: int = 400, seed: int = SEED) -> list[dict]:
    rnd = random.Random(seed)
    rows = []
    for i in range(n):
        k = rnd.randint(3, 8)
        ys = sorted(rnd.sample(range(4, 152, 8), k))
        holds = [
            {
                "id": f"syn-{rnd.randrange(0, 144, 8)}-{y}",
                "x": float(rnd.randrange(0, 144, 8)),
                "y": float(y),
                "role": "START" if j == 0 else ("FINISH" if j == k - 1 else "HAND"),
            }
            for j, y in enumerate(ys)
        ]
        angle = rnd.choice([20, 30, 40, 45, 50])
        grade = 10 + ys[-1] / 12 + angle / 10 + rnd.random()
        rows.append(
            {
                "route_uuid": f"syn-{i}",
                "angle_deg": angle,
                "holds": holds,
                "grade": round(grade, 2),
                "ascents": 5,
            }
        )
    return rows


def build(registry_root: Path) -> Path:
    target = Path(registry_root) / VERSION
    if target.exists():
        shutil.rmtree(target)
    model = Gbm(use_holds=False, learning_rate=0.1, max_leaf_nodes=15, max_iter=50, seed=SEED)
    model.fit(to_split("synthetic", synthetic_rows()))
    with tempfile.TemporaryDirectory() as tmp:
        (Path(tmp) / "model.pkl").write_bytes(pickle.dumps(model))
        return registry.package(
            kind="sklearn-hgb",
            source_dir=Path(tmp),
            model_version=VERSION,
            training_manifest_id="model-a-000000000000",
            training_run_id="synthetic-smoke",
            metrics_ref="none (synthetic smoke model)",
            registry_root=registry_root,
            license_review_status="SYNTHETIC",
        )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Package the synthetic smoke model")
    parser.add_argument("--registry-root", type=Path, default=registry.REGISTRY_ROOT)
    args = parser.parse_args(argv)
    folder = build(args.registry_root)
    result = registry.verify(folder)
    print(json.dumps({"folder": str(folder), "status": result.status, "reason": result.reason}))
    return 0 if result.status == "READY" else 1


if __name__ == "__main__":
    sys.exit(main())
