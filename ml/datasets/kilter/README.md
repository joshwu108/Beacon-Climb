# ml/datasets/kilter (placeholder)

Nothing in this directory is tracked except this README.

- Raw input lives only at `data/raw/kilter_splits.sqlite` (gitignored, placed by the human).
  No agent downloads, scrapes or syncs it.
- Derived artifacts (e.g. parquet extracts, parsed `frames`, feature tables) may be written
  here locally by later tickets (BC-0103 importer, BC-0201 split/leakage audit) and must be
  gitignored. As of 2026-10-14 `.gitignore` covers only `data/`; `git check-ignore -v
  ml/datasets/kilter/x.parquet` printed nothing (exit 1), i.e. NOT ignored. Until the
  integrator adds an ignore rule for this directory, write derived artifacts under
  `data/derived/` instead. They are not to be committed, published or redistributed: rights are
  UNKNOWN-NEEDS-HUMAN per `docs/data-source-audit.md`.
- Every derived artifact must name the manifest (`ml/datasets/manifests/kilter-<sha12>.json`)
  it was built from.

Status 2026-10-14: BLOCKED, sqlite not present. See `ml/datasets/MANIFEST.md`.
