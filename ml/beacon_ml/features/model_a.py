"""Model A features v1 (BC-0202): geometry and angle of one climb at one angle.

Input is a frozen dataset row's ``holds`` (``[{id, x, y, role}]`` in raw Kilter board units)
and ``angle_deg``; nothing else (``ascents`` and ids are metadata, never features).

Two representations:

* ``geometry_vector(holds, angle)`` -> fixed-length list of floats, names in
  ``GEOMETRY_FEATURES`` and formulas in ``docs/ml/model-a-features.md``. Deterministic, pure
  Python, so Java/TS can reproduce it from the golden fixtures.
* ``HoldVocabulary`` -> hold-identity indicators (which physical holds are used for hands and
  for feet). The vocabulary is fitted on the training split only.

"Hand holds" are roles START, HAND and FINISH; "feet" are FOOT. Distances are Euclidean in raw
board units. Missing values (e.g. no feet) are the sentinel ``MISSING = -1.0``.
"""

from __future__ import annotations

import math
from collections.abc import Iterable, Sequence
from dataclasses import dataclass
from itertools import pairwise

FEATURE_VERSION = "model-a-features-v1"
MISSING = -1.0
HAND_ROLES = ("START", "HAND", "FINISH")
FOOT_ROLE = "FOOT"
ROLES = ("START", "HAND", "FOOT", "FINISH")
# Raw-coordinate frame of Kilter layout 1, sets 1 and 20 (ml/datasets/kilter/README.md).
BOARD_X_MIN, BOARD_X_SPAN = -20.0, 184.0
BOARD_Y_MIN, BOARD_Y_SPAN = 4.0, 172.0

GEOMETRY_FEATURES = (
    "angle_deg",
    "n_holds",
    "n_start",
    "n_hand",
    "n_foot",
    "n_finish",
    "hand_x_min",
    "hand_x_max",
    "hand_y_min",
    "hand_y_max",
    "hand_width",
    "hand_height",
    "hand_x_std",
    "hand_y_std",
    "start_x",
    "start_y",
    "finish_x",
    "finish_y",
    "start_finish_dx",
    "start_finish_dy",
    "start_finish_dist",
    "chain_gap_max",
    "chain_gap_mean",
    "chain_gap_std",
    "chain_dy_max",
    "chain_dx_max",
    "nn_mean",
    "nn_max",
    "pair_mean",
    "pair_std",
    "pair_max",
    "hand_density",
    "foot_y_mean",
    "foot_to_hand_mean",
    "foot_below_start",
)


@dataclass(frozen=True)
class Point:
    x: float
    y: float


def _mean(values: Sequence[float]) -> float:
    return sum(values) / len(values) if values else MISSING


def _std(values: Sequence[float]) -> float:
    """Population standard deviation; 0 for one value, MISSING for none."""
    if not values:
        return MISSING
    mu = sum(values) / len(values)
    return math.sqrt(sum((v - mu) ** 2 for v in values) / len(values))


def _dist(a: Point, b: Point) -> float:
    return math.hypot(a.x - b.x, a.y - b.y)


def _centroid(points: Sequence[Point]) -> Point | None:
    if not points:
        return None
    return Point(_mean([p.x for p in points]), _mean([p.y for p in points]))


def _points(holds: Iterable[dict], roles: Iterable[str]) -> list[Point]:
    wanted = set(roles)
    return [Point(float(h["x"]), float(h["y"])) for h in holds if h["role"] in wanted]


def _chain(hands: Sequence[Point]) -> list[tuple[Point, Point]]:
    """Consecutive pairs after sorting hand holds bottom to top (ties left to right)."""
    ordered = sorted(hands, key=lambda p: (p.y, p.x))
    return list(pairwise(ordered))


def _nearest(point: Point, others: Sequence[Point]) -> float:
    return min(_dist(point, o) for o in others)


def geometry_vector(holds: Sequence[dict], angle_deg: int) -> list[float]:
    """Return the feature values in ``GEOMETRY_FEATURES`` order."""
    hands = _points(holds, HAND_ROLES)
    feet = _points(holds, (FOOT_ROLE,))
    starts = _points(holds, ("START",))
    finishes = _points(holds, ("FINISH",))
    count = {role: sum(1 for h in holds if h["role"] == role) for role in ROLES}

    hx, hy = [p.x for p in hands], [p.y for p in hands]
    width = max(hx) - min(hx) if hands else MISSING
    height = max(hy) - min(hy) if hands else MISSING

    start, finish = _centroid(starts), _centroid(finishes)
    if start and finish:
        dx, dy, dd = finish.x - start.x, finish.y - start.y, _dist(start, finish)
    else:
        dx = dy = dd = MISSING

    chain = _chain(hands)
    gaps = [_dist(a, b) for a, b in chain]
    nn = [_nearest(p, [o for j, o in enumerate(hands) if j != i]) for i, p in enumerate(hands)]
    nn = nn if len(hands) > 1 else []
    pairs = [_dist(a, b) for i, a in enumerate(hands) for b in hands[i + 1 :]]
    area = width * height if hands and width > 0 and height > 0 else 0.0

    values = {
        "angle_deg": float(angle_deg),
        "n_holds": float(len(holds)),
        "n_start": float(count["START"]),
        "n_hand": float(count["HAND"]),
        "n_foot": float(count["FOOT"]),
        "n_finish": float(count["FINISH"]),
        "hand_x_min": min(hx) if hands else MISSING,
        "hand_x_max": max(hx) if hands else MISSING,
        "hand_y_min": min(hy) if hands else MISSING,
        "hand_y_max": max(hy) if hands else MISSING,
        "hand_width": width,
        "hand_height": height,
        "hand_x_std": _std(hx),
        "hand_y_std": _std(hy),
        "start_x": start.x if start else MISSING,
        "start_y": start.y if start else MISSING,
        "finish_x": finish.x if finish else MISSING,
        "finish_y": finish.y if finish else MISSING,
        "start_finish_dx": dx,
        "start_finish_dy": dy,
        "start_finish_dist": dd,
        "chain_gap_max": max(gaps) if gaps else MISSING,
        "chain_gap_mean": _mean(gaps),
        "chain_gap_std": _std(gaps),
        "chain_dy_max": max((b.y - a.y for a, b in chain), default=MISSING),
        "chain_dx_max": max((abs(b.x - a.x) for a, b in chain), default=MISSING),
        "nn_mean": _mean(nn),
        "nn_max": max(nn) if nn else MISSING,
        "pair_mean": _mean(pairs),
        "pair_std": _std(pairs),
        "pair_max": max(pairs) if pairs else MISSING,
        "hand_density": len(hands) / area if area else MISSING,
        "foot_y_mean": _mean([p.y for p in feet]),
        "foot_to_hand_mean": _mean([_nearest(f, hands) for f in feet]) if hands else MISSING,
        "foot_below_start": (start.y - _mean([p.y for p in feet])) if (start and feet) else MISSING,
    }
    return [values[name] for name in GEOMETRY_FEATURES]


def normalized_xy(x: float, y: float) -> tuple[float, float]:
    """Raw board units -> [0, 1] over the layout-1 frame (for neural models)."""
    return (x - BOARD_X_MIN) / BOARD_X_SPAN, (y - BOARD_Y_MIN) / BOARD_Y_SPAN


class HoldVocabulary:
    """Hold-identity indicators: one column per (hold, use) with use in {hand, foot}.

    Fitted on training rows only; holds unseen in training map to nothing (no column).
    """

    def __init__(self, hold_ids: Iterable[str]):
        self.hold_ids = sorted(set(hold_ids))
        self.index = {h: i for i, h in enumerate(self.hold_ids)}

    @classmethod
    def fit(cls, rows: Iterable[dict]) -> HoldVocabulary:
        return cls(h["id"] for row in rows for h in row["holds"])

    @property
    def width(self) -> int:
        return 2 * len(self.hold_ids)

    def columns(self, holds: Sequence[dict]) -> list[int]:
        """Indices set to 1: hand use at ``i``, foot use at ``len + i``."""
        size = len(self.hold_ids)
        out = []
        for hold in holds:
            i = self.index.get(hold["id"])
            if i is not None:
                out.append(size + i if hold["role"] == FOOT_ROLE else i)
        return sorted(set(out))
