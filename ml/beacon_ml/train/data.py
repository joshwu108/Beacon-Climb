"""Load a frozen Model A dataset (BC-0201) into arrays for training (BC-0203)."""

from __future__ import annotations

import importlib.util
import sys
from dataclasses import dataclass
from pathlib import Path

import numpy as np

from beacon_ml.features import model_a as fa

ML_ROOT = Path(__file__).resolve().parents[2]
ACCESS_PATH = ML_ROOT / "datasets" / "model_a" / "access.py"


def _access():
    """datasets/ is a script directory, not an installed package; load access.py by path."""
    name = "beacon_model_a_access"
    if name not in sys.modules:
        spec = importlib.util.spec_from_file_location(name, ACCESS_PATH)
        module = importlib.util.module_from_spec(spec)
        sys.modules[name] = module
        spec.loader.exec_module(module)
    return sys.modules[name]


access = _access()


@dataclass(frozen=True)
class Split:
    """One split as arrays. ``rows`` keeps holds for hold-identity features."""

    name: str
    rows: list[dict]
    geometry: np.ndarray  # float64 [n, len(GEOMETRY_FEATURES)]
    angle: np.ndarray  # int [n]
    grade: np.ndarray  # float64 [n]
    ascents: np.ndarray  # int [n]
    route: np.ndarray  # int [n], route index for cluster bootstrap

    def __len__(self) -> int:
        return len(self.rows)


def to_split(name: str, rows: list[dict]) -> Split:
    routes: dict[str, int] = {}
    return Split(
        name=name,
        rows=rows,
        geometry=np.array(
            [fa.geometry_vector(r["holds"], r["angle_deg"]) for r in rows], dtype=np.float64
        ).reshape(len(rows), len(fa.GEOMETRY_FEATURES)),
        angle=np.array([r["angle_deg"] for r in rows], dtype=np.int64),
        grade=np.array([r.get("grade", np.nan) for r in rows], dtype=np.float64),
        ascents=np.array([r.get("ascents", 0) for r in rows], dtype=np.int64),
        route=np.array(
            [routes.setdefault(r.get("route_uuid", i), len(routes)) for i, r in enumerate(rows)]
        ),
    )


def load(
    manifest: dict, split: str, *, data_root: Path | None = None, unseal_purpose: str | None = None
) -> Split:
    kwargs = {"unseal_purpose": unseal_purpose}
    if data_root is not None:
        kwargs["data_root"] = data_root
    return to_split(split, list(access.load_split(manifest, split, **kwargs)))


def hold_matrix(rows: list[dict], vocab: fa.HoldVocabulary) -> np.ndarray:
    """Dense float32 hold-identity indicators [n, vocab.width]."""
    out = np.zeros((len(rows), vocab.width), dtype=np.float32)
    for i, row in enumerate(rows):
        out[i, vocab.columns(row["holds"])] = 1.0
    return out
