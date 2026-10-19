"""Read a frozen Model A dataset (BC-0201) with hash checks and a sealed test split.

``load_split(manifest, "train")`` and ``"val"`` verify the file sha256 against the committed
manifest. ``"test"`` additionally needs ``unseal_purpose`` and appends one line per access to
``test-access.log`` next to the data, so the evaluation report (BC-0203/0209) can show the test
set was opened once per frozen candidate, after selection on validation.
"""

from __future__ import annotations

import hashlib
import json
from collections.abc import Iterator
from datetime import UTC, datetime
from pathlib import Path

DEFAULT_DATA_ROOT = Path(__file__).resolve().parents[3] / "data"
SPLIT_ORDER = ("train", "val", "test")  # same order as build.py
HASH_CHUNK = 1 << 20
TEST_LOG = "test-access.log"


class SealViolation(Exception):
    """The test split was requested without a purpose, or a file does not match its hash."""


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while chunk := handle.read(HASH_CHUNK):
            digest.update(chunk)
    return digest.hexdigest()


def read_manifest(path: Path) -> dict:
    return json.loads(Path(path).read_text(encoding="utf-8"))


def dataset_dir(manifest: dict, data_root: Path = DEFAULT_DATA_ROOT) -> Path:
    return Path(data_root) / "datasets" / manifest["dataset_id"]


def load_split(
    manifest: dict,
    split: str,
    *,
    unseal_purpose: str | None = None,
    data_root: Path = DEFAULT_DATA_ROOT,
    now: datetime | None = None,
) -> Iterator[dict]:
    """Check the hash (and the seal for test) now, then return a lazy row iterator."""
    if split not in SPLIT_ORDER:
        raise ValueError(f"unknown split {split!r}")
    path = dataset_dir(manifest, data_root) / f"{split}.jsonl"
    if _sha256(path) != manifest["files_sha256"][split]:
        raise SealViolation(f"{path} does not match the frozen manifest {manifest['dataset_id']}")
    if split == manifest["sealed_split"]:
        if not unseal_purpose or not unseal_purpose.strip():
            raise SealViolation("the test split is sealed: pass unseal_purpose")
        stamp = (now or datetime.now(UTC)).strftime("%Y-%m-%dT%H:%M:%SZ")
        with (path.parent / TEST_LOG).open("a", encoding="utf-8") as log:
            log.write(json.dumps({"at": stamp, "purpose": unseal_purpose.strip()}) + "\n")
    return _rows(path)


def _rows(path: Path) -> Iterator[dict]:
    with path.open(encoding="utf-8") as handle:
        for line in handle:
            yield json.loads(line)


def list_test_accesses(manifest: dict, data_root: Path = DEFAULT_DATA_ROOT) -> list[dict]:
    log = dataset_dir(manifest, data_root) / TEST_LOG
    if not log.exists():
        return []
    return [json.loads(line) for line in log.read_text(encoding="utf-8").splitlines()]
