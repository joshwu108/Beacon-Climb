# Runbook: remote training on Modal (BC-0213)

Scope (ADR-0007 exception, owner approval 2026-10-15 and 2026-10-18): Model A **validation-stage**
training only. Inference, the API, streaming and demos stay local. The sealed test split never
leaves this machine; test evaluation is always `make train-deepsets-test` locally.

## One-time setup (owner)

```sh
cd ml && uv sync --group train --group torch-cpu --group remote   # installs the Modal client (1.6.1)
uv run --group remote modal setup        # opens a browser, writes ~/.modal.toml
uv run --group remote modal token info   # verify
```

Non-interactive alternative: `uv run --group remote modal token set --token-id <id> --token-secret <secret>`,
or export `MODAL_TOKEN_ID` / `MODAL_TOKEN_SECRET`. Tokens stay in Modal's own config or the shell;
the repo never reads, stores or logs them, and CI never calls Modal.

## Run

```sh
make train-remote RIGHTS_SIGNED=1                 # default config configs/model_a_deepsets.json, GPU T4
make train-remote RIGHTS_SIGNED=1 GPU=L4 CONFIG=configs/model_a_deepsets.json
```

What happens:
1. Gate: the Kilter dataset is non-synthetic, so the run is refused before any upload unless
   `RIGHTS_SIGNED=1` and `docs/data-source-audit.md` carries the signed acceptance line.
2. The train and val files of the frozen dataset are hash-checked and uploaded to the Modal
   Volume `beacon-model-a-data` (rows contain no personal columns). Test is not uploaded.
3. The same `run_deepsets.stage_val` code runs on the GPU (identical configs and seeds; the
   shuffling order is generated on CPU), and the report and checkpoint come back to
   `reports/model-a/<run>-modal/` and `data/models/model-a-deepsets/<run>/` (gitignored),
   with `environment: modal:<gpu>`.
4. Evaluate locally, once per frozen candidate:
   `make train-deepsets-test CANDIDATE=data/models/model-a-deepsets/<run>/candidate.json SELECTION=reports/model-a/20261019T223149Z-val/selection.json`.
   Note: the test split of model-a-100d08f359ca has already been opened twice; a new claim
   needs a new held-out split or a prospective check (docs/model-cards/model-a.md).

## Cost

Modal bills per second of GPU time. The report records GPU type and wall time; it records an
estimate only if `usd_per_hour` is set in the config (no prices are assumed). Check the Modal
dashboard for the actual charge; stay within the included credits unless the owner writes a cap
into ADR-0007.

## Clean up

```sh
uv run --directory ml --group remote modal volume rm -r beacon-model-a-data /datasets   # remove uploaded rows
uv run --directory ml --group remote modal volume delete beacon-model-a-data             # remove the volume
```
