"""Model A evaluation metrics with climb-level (cluster) bootstrap intervals (BC-0203).

Errors are in Aurora difficulty units (1 unit = one Font sub-grade, e.g. 6a -> 6a+). V-grade
metrics map a difficulty to its V number through the dataset's own grade names
("6a+/V3" -> 3). Rows of one climb at several angles are correlated, so intervals resample
whole climbs, never single rows.
"""

from __future__ import annotations

import math
import re

import numpy as np

V_PATTERN = re.compile(r"/V(\d+)$")
CI_LEVEL = 0.95


def v_table(grade_names: dict[int, str]) -> tuple[int, int, dict[int, int]]:
    table = {}
    for difficulty, name in grade_names.items():
        match = V_PATTERN.search(name)
        if match:
            table[int(difficulty)] = int(match.group(1))
    return min(table), max(table), table


def to_v(values: np.ndarray, grade_names: dict[int, str]) -> np.ndarray:
    """Nearest difficulty (halves up), clamped to the known range, then its V number."""
    low, high, table = v_table(grade_names)
    nearest = np.clip(np.floor(values + 0.5).astype(int), low, high)
    return np.array([table[int(d)] for d in nearest])


def point_metrics(pred: np.ndarray, truth: np.ndarray, grade_names: dict[int, str]) -> dict:
    err = pred - truth
    v_pred, v_true = to_v(pred, grade_names), to_v(truth, grade_names)
    return {
        "n": len(truth),
        "mae": float(np.mean(np.abs(err))),
        "rmse": float(math.sqrt(np.mean(err**2))),
        "bias": float(np.mean(err)),
        "within_1_unit": float(np.mean(np.abs(err) <= 1.0)),
        "v_exact": float(np.mean(v_pred == v_true)),
        "v_within_1": float(np.mean(np.abs(v_pred - v_true) <= 1)),
    }


def _route_sums(values: np.ndarray, route: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    n_routes = int(route.max()) + 1 if len(route) else 0
    sums = np.bincount(route, weights=values, minlength=n_routes)
    counts = np.bincount(route, minlength=n_routes).astype(np.float64)
    return sums, counts


def bootstrap_ci(per_row: np.ndarray, route: np.ndarray, *, resamples: int, seed: int) -> list:
    """95% percentile interval of the mean of ``per_row``, resampling climbs."""
    if len(per_row) == 0:
        return [None, None]
    sums, counts = _route_sums(per_row, route)
    present = counts > 0
    sums, counts = sums[present], counts[present]
    rng = np.random.default_rng(seed)
    weights = rng.multinomial(len(sums), np.full(len(sums), 1 / len(sums)), size=resamples)
    means = (weights @ sums) / (weights @ counts)
    alpha = (1 - CI_LEVEL) / 2
    return [float(np.quantile(means, alpha)), float(np.quantile(means, 1 - alpha))]


def evaluate(pred, truth, route, grade_names, *, resamples: int, seed: int) -> dict:
    out = point_metrics(pred, truth, grade_names)
    abs_err = np.abs(pred - truth)
    out["mae_ci95"] = bootstrap_ci(abs_err, route, resamples=resamples, seed=seed)
    v_hit = (np.abs(to_v(pred, grade_names) - to_v(truth, grade_names)) <= 1).astype(float)
    out["v_within_1_ci95"] = bootstrap_ci(v_hit, route, resamples=resamples, seed=seed)
    return out


def paired_mae_difference(pred_a, pred_b, truth, route, *, resamples: int, seed: int) -> dict:
    """MAE(a) - MAE(b) with a paired climb-level bootstrap interval (negative = a better)."""
    diff = np.abs(pred_a - truth) - np.abs(pred_b - truth)
    return {
        "mae_a_minus_b": float(np.mean(diff)),
        "ci95": bootstrap_ci(diff, route, resamples=resamples, seed=seed),
    }
