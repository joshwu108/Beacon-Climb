"""Register a packaged model version in Postgres (BC-0205, ``make register-model``).

    python -I app/register.py --dir ../../data/models/registry/<model_version> [--activate]

Runs ``beacon_ml.registry.verify`` (file hashes, artifact digest, fixture predictions) and
inserts one ``model_versions`` row with status READY or REJECTED. ``--activate`` makes a READY
version the active one for its family in the same transaction; a REJECTED version is never
activated, so the previously active version stays in place. Exit 0 READY, 1 REJECTED, 2 usage.

The database URL comes from ``BEACON_ML_DB_URL`` (e.g.
``postgresql://beacon_app:<password>@127.0.0.1:5432/beacon``); it is never logged.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path

from beacon_ml import registry

INSERT = """
insert into model_versions (model_version, family, kind, artifact_digest, input_schema_version,
  training_manifest_id, training_run_id, metrics_ref, license_review_status, status,
  rejection_reason, active, activated_at)
values (%(model_version)s, %(family)s, %(kind)s, %(artifact_digest)s, %(input_schema_version)s,
  %(training_manifest_id)s, %(training_run_id)s, %(metrics_ref)s, %(license_review_status)s,
  %(status)s, %(reason)s, %(active)s, case when %(active)s then now() end)
"""
DEACTIVATE = "update model_versions set active = false where family = %(family)s and active"


def row_for(verified: registry.Verified, activate: bool) -> dict:
    m = verified.manifest
    return {
        "model_version": m["model_version"],
        "family": m["family"],
        "kind": m["kind"],
        "artifact_digest": m["artifact_digest"],
        "input_schema_version": m["input_schema_version"],
        "training_manifest_id": m["training_manifest_id"],
        "training_run_id": m["training_run_id"],
        "metrics_ref": m["metrics_ref"],
        "license_review_status": m["license_review_status"],
        "status": verified.status,
        "reason": verified.reason,
        "active": activate and verified.status == "READY",
    }


def write(row: dict, url: str) -> None:
    import psycopg

    with psycopg.connect(url) as conn, conn.transaction():
        if row["active"]:
            conn.execute(DEACTIVATE, row)
        conn.execute(INSERT, row)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Verify and register a model version")
    parser.add_argument("--dir", type=Path, required=True)
    parser.add_argument("--activate", action="store_true")
    args = parser.parse_args(argv)
    url = os.environ.get("BEACON_ML_DB_URL")
    if not url:
        print("BEACON_ML_DB_URL is not set", file=sys.stderr)
        return 2
    verified = registry.verify(args.dir)
    if not verified.manifest.get("model_version"):
        print(f"REJECTED (not registrable): {verified.reason}", file=sys.stderr)
        return 1
    row = row_for(verified, args.activate)
    write(row, url)
    print(json.dumps({k: row[k] for k in ("model_version", "status", "reason", "active")}))
    return 0 if verified.status == "READY" else 1


if __name__ == "__main__":
    sys.exit(main())
