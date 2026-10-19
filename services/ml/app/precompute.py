"""Batch precompute of Model A outputs into route_model_outputs (BC-0207).

    python -I -m app.precompute --dir ../../data/models/registry/<model_version> \
        --board kilter-l1-s10 [--batch 2000]

The artifact is verified first (same checks as serving) and must already be registered READY
in ``model_versions`` (foreign key). Variants that already have a row for this model version
are skipped by the query and by ``ON CONFLICT DO NOTHING``, so a re-run is a no-op.

Only boards whose provider matches the training data (``kilter``) are accepted: the model knows
Kilter hold ids and raw coordinates (``holds.geometry_meta_json`` x_raw / y_raw) and means
nothing on another board. ``BEACON_ML_DB_URL`` supplies the connection; it is never logged.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
from collections.abc import Iterable, Iterator
from pathlib import Path

from beacon_ml import registry

TRAINED_PROVIDER = "kilter"
SELECT = """
select v.variant_id, v.angle_deg, h.provider_hold_id,
       (h.geometry_meta_json->>'x_raw')::float8, (h.geometry_meta_json->>'y_raw')::float8, p.role
from route_variants v
join routes r on r.route_id = v.route_id
join route_placements p on p.variant_id = v.variant_id
join holds h on h.hold_id = p.hold_id
where r.board_id = %(board)s
  and not exists (select 1 from route_model_outputs o
                  where o.variant_id = v.variant_id and o.model_version = %(version)s)
order by v.variant_id, p.placement_order nulls last, h.provider_hold_id
"""
INSERT = """
insert into route_model_outputs (variant_id, model_version, predicted_difficulty, embedding)
values (%s, %s, %s, %s) on conflict do nothing
"""


class Refused(Exception):
    pass


def group_variants(rows: Iterable[tuple]) -> Iterator[tuple[object, dict]]:
    """Rows ordered by variant -> (variant_id, model input row). Missing raw coordinates refuse."""
    current, angle, holds = None, None, []
    for variant_id, angle_deg, hold_id, x_raw, y_raw, role in rows:
        if x_raw is None or y_raw is None:
            raise Refused(f"hold {hold_id} has no raw coordinates; not a Kilter-geometry board")
        if variant_id != current:
            if current is not None:
                yield current, {"angle_deg": angle, "holds": holds}
            current, angle, holds = variant_id, int(angle_deg), []
        holds.append({"id": hold_id, "x": x_raw, "y": y_raw, "role": role})
    if current is not None:
        yield current, {"angle_deg": angle, "holds": holds}


def batches(items: Iterator, size: int) -> Iterator[list]:
    batch = []
    for item in items:
        batch.append(item)
        if len(batch) == size:
            yield batch
            batch = []
    if batch:
        yield batch


def output_rows(batch, verified: registry.Verified) -> list[tuple]:
    inputs = [row for _, row in batch]
    grades = verified.predict(inputs)
    vectors = verified.embed(inputs) if verified.embed is not None else None
    out = []
    for i, (variant_id, _) in enumerate(batch):
        embedding = [float(x) for x in vectors[i]] if vectors is not None else None
        out.append(
            (variant_id, verified.manifest["model_version"], round(float(grades[i]), 3), embedding)
        )
    return out


def check_registered(conn, version: str) -> None:
    row = conn.execute(
        "select status from model_versions where model_version = %s", (version,)
    ).fetchone()
    if row is None or row[0] != "READY":
        raise Refused(f"{version} is not registered READY (run make register-model first)")


def check_board(conn, board: str) -> None:
    row = conn.execute("select provider from boards where board_id = %s", (board,)).fetchone()
    if row is None:
        raise Refused(f"board {board} does not exist")
    if row[0] != TRAINED_PROVIDER:
        raise Refused(
            f"board {board} has provider {row[0]}; the model was trained on "
            f"{TRAINED_PROVIDER} geometry"
        )


def run(url: str, model_dir: Path, board: str, batch_size: int) -> dict:
    import psycopg

    verified = registry.verify(model_dir)
    if verified.status != "READY":
        raise Refused(f"artifact {model_dir} is {verified.status}: {verified.reason}")
    version = verified.manifest["model_version"]
    started, written = time.perf_counter(), 0
    with psycopg.connect(url) as conn:
        check_registered(conn, version)
        check_board(conn, board)
        with conn.cursor(name="precompute_variants") as cur, psycopg.connect(url) as writer:
            cur.itersize = 20000
            cur.execute(SELECT, {"board": board, "version": version})
            for batch in batches(group_variants(cur), batch_size):
                with writer.transaction(), writer.cursor() as w:
                    w.executemany(INSERT, output_rows(batch, verified))
                    written += len(batch)
        total = conn.execute(
            "select count(*) from route_model_outputs o join route_variants v using (variant_id)"
            " join routes r using (route_id) where r.board_id = %s and o.model_version = %s",
            (board, version),
        ).fetchone()[0]
        variants = conn.execute(
            "select count(*) from route_variants v join routes r using (route_id)"
            " where r.board_id = %s",
            (board,),
        ).fetchone()[0]
    return {
        "model_version": version,
        "board": board,
        "written_this_run": written,
        "rows_for_version": total,
        "variants_on_board": variants,
        "seconds": round(time.perf_counter() - started, 1),
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Precompute Model A outputs (BC-0207)")
    parser.add_argument("--dir", type=Path, required=True)
    parser.add_argument("--board", required=True)
    parser.add_argument("--batch", type=int, default=2000)
    args = parser.parse_args(argv)
    url = os.environ.get("BEACON_ML_DB_URL")
    if not url:
        print("BEACON_ML_DB_URL is not set", file=sys.stderr)
        return 2
    try:
        summary = run(url, args.dir, args.board, args.batch)
    except Refused as exc:
        print(f"REFUSED: {exc}", file=sys.stderr)
        return 2
    print(json.dumps(summary))
    return 0


if __name__ == "__main__":
    sys.exit(main())
