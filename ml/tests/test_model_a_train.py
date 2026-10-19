"""Tests for beacon_ml.train (BC-0203) on a SYNTHETIC frozen dataset built in tmp_path."""

from __future__ import annotations

import importlib.util
import json
import random
import sys
from pathlib import Path

import pytest

np = pytest.importorskip("numpy")
pytest.importorskip("sklearn")

from beacon_ml.train import metrics, run

ML_ROOT = Path(__file__).resolve().parents[1]
NAMES = {
    10: "4a/V0",
    13: "5a/V1",
    15: "5c/V2",
    16: "6a/V3",
    18: "6b/V4",
    20: "6c/V5",
    22: "7a/V6",
    23: "7a+/V7",
    24: "7b/V8",
    26: "7c/V9",
}


def _load_build():
    spec = importlib.util.spec_from_file_location(
        "model_a_build_t", ML_ROOT / "datasets/model_a/build.py"
    )
    module = importlib.util.module_from_spec(spec)
    sys.modules["model_a_build_t"] = module
    spec.loader.exec_module(module)
    return module


def test_to_v_rounds_halves_up_and_clamps():
    v = metrics.to_v(np.array([15.49, 15.5, 9.0, 40.0]), NAMES)
    assert v.tolist() == [2, 3, 0, 9]


def test_point_metrics_known_values():
    m = metrics.point_metrics(np.array([16.0, 18.0]), np.array([16.0, 20.0]), NAMES)
    assert m["mae"] == 1.0 and m["bias"] == -1.0 and m["within_1_unit"] == 0.5
    assert m["v_exact"] == 0.5 and m["v_within_1"] == 1.0


def test_cluster_bootstrap_interval_brackets_the_mean_and_is_deterministic():
    rng = np.random.default_rng(0)
    values, route = rng.random(300), np.repeat(np.arange(100), 3)
    ci = metrics.bootstrap_ci(values, route, resamples=500, seed=1)
    assert ci[0] < values.mean() < ci[1]
    assert ci == metrics.bootstrap_ci(values, route, resamples=500, seed=1)


def test_paired_difference_sign():
    truth = np.zeros(10)
    out = metrics.paired_mae_difference(
        truth + 0.1, truth + 1.0, truth, np.arange(10), resamples=200, seed=0
    )
    assert out["mae_a_minus_b"] < 0 and out["ci95"][1] < 0


def _synthetic_frozen(tmp_path: Path):
    """Grade = f(height, angle) + noise on a 10x10 synthetic grid; uuid splits 80/10/10."""
    rnd = random.Random(7)
    holds = {f"h{r}{c}": (c * 16, r * 16) for r in range(10) for c in range(10)}
    routes, splits = [], {}
    for i in range(400):
        ids = rnd.sample(sorted(holds), rnd.randint(3, 8))
        ids.sort(key=lambda h: holds[h][1])
        roles = ["START"] + ["HAND"] * (len(ids) - 2) + ["FINISH"]
        top = holds[ids[-1]][1]
        variants = [
            {
                "angle_deg": a,
                "grade_numeric": round(10 + top / 12 + a / 10 + rnd.random(), 2),
                "grade_observation_count": rnd.randint(1, 20),
            }
            for a in (20, 40)
        ]
        uid = f"r{i:03d}"
        routes.append(
            {
                "type": "route",
                "provider_route_uuid": uid,
                "board_id": "b",
                "name": None,
                "setter_pseudonym": None,
                "tags": [],
                "active_from": None,
                "active_to": None,
                "placements": [{"provider_hold_id": h, "role": r} for h, r in zip(ids, roles)],
                "variants": variants,
            }
        )
        splits[uid] = "train" if i < 320 else ("val" if i < 360 else "test")
    cat = tmp_path / "data" / "catalog" / "k"
    cat.mkdir(parents=True)
    board = {
        "type": "board",
        "holds": [
            {"provider_hold_id": h, "x": 0, "y": 0, "geometry_meta": {"x_raw": x, "y_raw": y}}
            for h, (x, y) in holds.items()
        ],
    }
    manifest = {
        "type": "manifest",
        "content_sha256": "0" * 64,
        "version": "v",
        "synthetic": False,
        "license_review_status": "LOCAL_RESEARCH_ONLY",
    }
    (cat / "catalog.jsonl").write_text("\n".join(json.dumps(x) for x in [manifest, board, *routes]))
    (cat / "splits.json").write_text(json.dumps({"splits": splits}))
    import sqlite3

    db = tmp_path / "g.sqlite"
    con = sqlite3.connect(db)
    con.execute("CREATE TABLE difficulty_grades (difficulty INT, boulder_name TEXT)")
    con.executemany(
        "INSERT INTO difficulty_grades VALUES (?,?)",
        list(NAMES.items()) + [(d, f"x/V{d - 10}") for d in range(11, 30) if d not in NAMES],
    )
    con.commit()
    con.close()
    result = _load_build().freeze(cat, db, data_root=tmp_path / "data", manifest_dir=tmp_path / "m")
    return json.loads(Path(result["manifest"]).read_text())


CONFIG = {
    "seed": 1,
    "bootstrap_resamples": 100,
    "slices": {"ascents_min": [3]},
    "gbm_grid": {
        "use_holds": [False, True],
        "learning_rate": [0.1],
        "max_leaf_nodes": [15],
        "max_iter": [50, 100],
    },
}


def test_val_then_test_stage_end_to_end(tmp_path):
    manifest = _synthetic_frozen(tmp_path)
    names = run.grade_names(manifest)
    root = tmp_path / "data"
    out = tmp_path / "out"
    out.mkdir()
    val = run.stage_val(CONFIG, manifest, names, out, data_root=root)
    assert set(val["selection"]) == {"baselines", "gbm_geo", "gbm_geo_holds"}
    assert "median-by-angle" in val["results"]
    # the signal is height + angle, so the geometry model must beat the global median on val
    gbm = val["results"][next(k for k in val["results"] if k.startswith("gbm-geo["))]["all"]["mae"]
    assert gbm < val["results"]["median-global"]["all"]["mae"]
    access = run.data.access
    assert access.list_test_accesses(manifest, root) == []  # val stage never opens test

    test = run.stage_test(
        CONFIG,
        manifest,
        names,
        out,
        out / "selection.json",
        "t1",
        data_root=root,
        models_dir=tmp_path / "models",
    )
    opened = access.list_test_accesses(manifest, root)
    assert len(opened) == 1 and "BC-0203 final test" in opened[0]["purpose"]
    assert test["artifact"]["sha256"] and (tmp_path / "models" / "t1" / "model.pkl").exists()
    assert all(
        r["ci95"][0] <= r["mae_a_minus_b"] <= r["ci95"][1]
        for r in test["paired_differences"].values()
    )
