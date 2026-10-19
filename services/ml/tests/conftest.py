"""Fixtures: tiny Model A artifacts trained on SYNTHETIC rows and packaged into a tmp registry."""

from __future__ import annotations

import pickle
import random
from pathlib import Path

import pytest
from beacon_ml import registry
from beacon_ml.train import data, deepsets
from beacon_ml.train.baselines import Gbm


def _rows(n: int = 120) -> list[dict]:
    rnd = random.Random(3)
    rows = []
    for i in range(n):
        k = rnd.randint(3, 7)
        ys = sorted(rnd.sample(range(4, 152, 8), k))
        holds = [
            {
                "id": f"h{y}-{j}",
                "x": float(rnd.randrange(0, 144, 8)),
                "y": float(y),
                "role": "START" if j == 0 else ("FINISH" if j == k - 1 else "HAND"),
            }
            for j, y in enumerate(ys)
        ]
        angle = rnd.choice([20, 40])
        rows.append(
            {
                "route_uuid": f"r{i}",
                "angle_deg": angle,
                "holds": holds,
                "grade": 10 + ys[-1] / 12 + angle / 10,
                "ascents": 5,
            }
        )
    return rows


@pytest.fixture(scope="session")
def registry_root(tmp_path_factory) -> Path:
    root = tmp_path_factory.mktemp("registry")
    rows = _rows()
    gbm_src = tmp_path_factory.mktemp("gbm")
    model = Gbm(use_holds=False, learning_rate=0.1, max_leaf_nodes=7, max_iter=20, seed=1)
    (gbm_src / "model.pkl").write_bytes(pickle.dumps(model.fit(data.to_split("t", rows))))
    common = {
        "training_manifest_id": "model-a-000000000000",
        "training_run_id": "test",
        "metrics_ref": "docs/x",
        "registry_root": root,
    }
    registry.package(
        kind="sklearn-hgb", source_dir=gbm_src, model_version="model-a-gbm-t", **common
    )

    import json

    import torch

    ds_src = tmp_path_factory.mktemp("ds")
    vocab = deepsets.TokenVocab.fit(rows)
    enc = deepsets.encode(rows, vocab, 12)
    net, _ = deepsets.train(
        enc,
        enc,
        vocab.size,
        seed=1,
        epochs=2,
        patience=2,
        batch=32,
        lr=0.003,
        weight_decay=0.0,
        hidden=16,
        log=lambda _m: None,
    )
    torch.save(net.state_dict(), ds_src / "state_dict.pt")
    (ds_src / "vocab.json").write_text(json.dumps(sorted(vocab.index, key=vocab.index.get)))
    (ds_src / "candidate.json").write_text(
        json.dumps({"vocab_size": vocab.size, "hidden": 16, "dropout": 0.0, "max_holds": 12})
    )
    registry.package(
        kind="torch-deepsets", source_dir=ds_src, model_version="model-a-ds-t", **common
    )
    return root
