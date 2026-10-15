# ml/datasets

| Directory | Contents | Status |
|---|---|---|
| `synthetic/` | **SYNTHETIC** demo board generator and committed catalog-v1 fixture (BC-0104). Grades are a toy function, not a model. See [`synthetic/README.md`](synthetic/README.md). | Committed, deterministic, schema-validated in CI |
| `kilter/` | Placeholder for the Kilter extractor (BC-0103). No data tracked. See [`kilter/README.md`](kilter/README.md). | BLOCKED on data rights |
| `manifests/` | Dataset manifests (hashes and counts only). See [`MANIFEST.md`](MANIFEST.md). | PENDING |
| `grade_scale.py` | Reference implementation of the grade ambiguity policy (BC-0014). | Done |

Rule: the only tracked dataset files are synthetic ones whose first line is a catalog-v1 manifest
with `"synthetic": true` (enforced by `scripts/ci/check-no-licensed-data.sh`). Raw or derived
licensed data lives only under the gitignored `data/`.
