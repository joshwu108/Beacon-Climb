"""Model A baselines (BC-0203): lookup medians and gradient boosting.

Every model has ``fit(train: Split) -> self`` and ``predict(split: Split) -> np.ndarray``.
Lookup medians fall back to the coarser table when a cell is empty in training.
"""

from __future__ import annotations

from dataclasses import dataclass, field

import numpy as np
from sklearn.ensemble import HistGradientBoostingRegressor

from beacon_ml.features import model_a as fa

from .data import Split, hold_matrix

N_HOLDS = fa.GEOMETRY_FEATURES.index("n_holds")
HOLD_BUCKETS = (3, 6, 9, 12, 16, 21, 51)  # upper-exclusive edges of n_holds buckets


class GlobalMedian:
    name = "median-global"

    def fit(self, train: Split):
        self.value = float(np.median(train.grade))
        return self

    def predict(self, split: Split) -> np.ndarray:
        return np.full(len(split), self.value)


class AngleMedian:
    name = "median-by-angle"

    def fit(self, train: Split):
        self.fallback = float(np.median(train.grade))
        self.table = {
            int(a): float(np.median(train.grade[train.angle == a])) for a in np.unique(train.angle)
        }
        return self

    def predict(self, split: Split) -> np.ndarray:
        return np.array([self.table.get(int(a), self.fallback) for a in split.angle])


def _bucket(n_holds: np.ndarray) -> np.ndarray:
    return np.searchsorted(HOLD_BUCKETS, n_holds, side="right")


class AngleHoldsMedian:
    name = "median-by-angle-and-holds"

    def fit(self, train: Split):
        self.by_angle = AngleMedian().fit(train)
        buckets = _bucket(train.geometry[:, N_HOLDS])
        self.table = {}
        for a, b in set(zip(train.angle.tolist(), buckets.tolist(), strict=True)):
            mask = (train.angle == a) & (buckets == b)
            self.table[(a, b)] = float(np.median(train.grade[mask]))
        return self

    def predict(self, split: Split) -> np.ndarray:
        buckets = _bucket(split.geometry[:, N_HOLDS])
        fallback = self.by_angle.predict(split)
        return np.array(
            [
                self.table.get((int(a), int(b)), f)
                for a, b, f in zip(split.angle, buckets, fallback, strict=True)
            ]
        )


@dataclass
class Gbm:
    """Histogram gradient boosting on geometry, optionally plus hold identity."""

    use_holds: bool
    learning_rate: float
    max_leaf_nodes: int
    max_iter: int
    seed: int
    vocab: fa.HoldVocabulary | None = field(default=None, repr=False)

    @property
    def name(self) -> str:
        kind = "gbm-geo+holds" if self.use_holds else "gbm-geo"
        return f"{kind}[lr={self.learning_rate},leaves={self.max_leaf_nodes},iter={self.max_iter}]"

    def _x(self, split: Split) -> np.ndarray:
        geometry = split.geometry.astype(np.float32)
        if not self.use_holds:
            return geometry
        return np.hstack([geometry, hold_matrix(split.rows, self.vocab)])

    def fit(self, train: Split):
        if self.use_holds:
            self.vocab = fa.HoldVocabulary.fit(train.rows)
        self.model = HistGradientBoostingRegressor(
            learning_rate=self.learning_rate,
            max_leaf_nodes=self.max_leaf_nodes,
            max_iter=self.max_iter,
            early_stopping=False,
            random_state=self.seed,
        )
        self.model.fit(self._x(train), train.grade)
        return self

    def predict(self, split: Split) -> np.ndarray:
        return self.model.predict(self._x(split))
