# BC-0203 / BC-0204 evidence (Model A on `model-a-100d08f359ca`)

Aggregate reports copied from the gitignored `reports/model-a/<run>/metrics.json`; they hold
metrics, configs, hashes and hardware only, never rows, holds or weights. Rendered into
`docs/model-cards/model-a.md` by `python -I -m beacon_ml.model_card`.

| File | Run | What |
|---|---|---|
| `val-20261019T223149Z.json` | baseline val | all baselines and the 8-point gradient-boosting grid on val |
| `selection-20261019T223149Z.json` | baseline val | frozen grid choice per feature set |
| `test-20261019T230345Z.json` | baseline test | frozen candidates on the sealed test split (first opening) |
| `test-access.log` | — | every opening of the test split (two in total) |
| `../BC-0204/val-single-20261019T225127Z.json` | Deep Sets val | first single-config run (reported, outside the sweep) |
| `../BC-0204/val-sweep-20261019T225443Z.json` | Deep Sets val | the pre-declared 4-variant sweep; trial 0 frozen |
| `../BC-0204/test-20261019T231047Z.json` | Deep Sets test | frozen Deep Sets vs frozen gradient boosting (second opening) |

**Known recording error.** The baseline val report lists `git.commit` 114d141 because, until
114d141 itself, the git state was read when a run ended rather than when it started. The code
that ran was f155c64; the baseline training files are identical between f155c64 and 114d141
(114d141 only moved the git capture to run start). Later reports record the state at start.
The Deep Sets val sweep (72ca0e5) and both test runs carry correct start commits; the Deep
Sets test started with uncommitted registry and smoke-model files that are not on the training
path.
