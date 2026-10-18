# Evidence ledger

This ledger is the only place where a measured result about Beacon Climb may be recorded. The README, pitch material, model cards and handoffs may cite a number only if it appears here with a source commit, the command that produced it and a raw artifact. PRD §18 defines the policy; this file is its table.

**Status (2026-10-18): first rows.** Only the rows below are measured. Every other performance, dataset, model, replay, experiment-safety and product-validation number that appears anywhere in `planning/` is a target or an illustration, not a result.

## Ledger

| Claim | Source commit | Measurement command | Dataset/version | Environment | Raw artifact | Verified by |
|---|---|---|---|---|---|---|
| Slice runs from a clean clone (Gate G1, BC-0125): `git clone` of d1ab5e3 (656 tracked files) into /private/tmp/beacon-g1-clone, fresh `.env` from `.env.example`, then `make reset-db` (a pgdata volume from an earlier checkout existed), `make dev` exit 0 in 20 s, `make seed-demo` exit 0 in 3 s, `make test` exit 0 in 54 s, `make test-e2e` exit 0 in 8 s, `make down` exit 0; attempt 4 of 4 (attempts 1 and 3 failed on the shared volume, attempt 2 on two api tests pinned to angle 35; see the attempt-*.txt files) | d1ab5e3 | docs/evidence/BC-0125/clean-clone-run.txt lists every command verbatim (runner: a bash script that enters the clone and runs `pnpm install --frozen-lockfile; uv sync --directory services/ml; make reset-db; make dev; make seed-demo; make test; make test-e2e; make down; make reset-db`) | synthetic demo board demo-board-v1 (ml/datasets/synthetic/fixtures, sha256 pinned in BC-0104); synthetic users only | Apple M5, 24 GB RAM, macOS 27.0 (26A428); Docker Desktop engine 29.2.1, 7836 MiB / 10 CPUs for containers; Homebrew OpenJDK 21.0.12.1; node 25.2.1; pnpm 10.34.7; local run | docs/evidence/BC-0125/ (clean-clone-run.txt + one log per step; attempt-1/2/3 logs kept) | pending: human review (recorded by the night-2 session, 2026-10-18) |
| `make test` in the clean clone: api 366 tests, 0 failed, 1 skipped (CatalogBenchIT opt-in; `BUILD SUCCESSFUL in 48s`); web 24 files / 289 tests passed; ml 1 passed (services/ml) and 56 passed / 1 skipped (ml) | d1ab5e3 | `make test` (see row above) | same | Apple M5, 24 GB RAM, macOS 27.0 (26A428); Docker Desktop engine 29.2.1, 7836 MiB / 10 CPUs for containers; Homebrew OpenJDK 21.0.12.1; node 25.2.1; pnpm 10.34.7; local run | docs/evidence/BC-0125/03-make-test.log | pending: human review |
| `make test-e2e` in the clean clone against the seeded stack: 6 passed in 7.1 s (contract-examples, demo-login, slice × chromium-desktop and chromium-mobile) | d1ab5e3 | `make test-e2e` (see row above) | same | Apple M5, 24 GB RAM, macOS 27.0 (26A428); Docker Desktop engine 29.2.1, 7836 MiB / 10 CPUs for containers; Homebrew OpenJDK 21.0.12.1; node 25.2.1; pnpm 10.34.7; local run | docs/evidence/BC-0125/04-make-test-e2e.log | pending: human review |

## Evidence categories (from PRD §18)

These are the categories a claim may fall into. They are aspirational until a row exists above.

- **Route model:** dataset size, unique routes and angle instances; route-UUID-disjoint grade MAE against a credible baseline, with intervals.
- **Personalization:** actual opt-in users and dated logs; out-of-time Brier or log loss against the grade-gap baseline; calibration and cold-start behaviour. No synthetic personalization result may be entered here.
- **Backend reliability:** synthetic event throughput and p99 latency on described hardware; complete error rate; the CI script that produced it.
- **Deterministic replay:** exact match on finite controlled fixtures with pinned artifacts; stated failure constraints; floating-point differences across environments explained.
- **Experiment safety:** injected bad model caught; threshold sample counts and rollback latency under test. Never a real conversion lift.
- **Product validation:** usability feedback, or a prospective study only if completed with consent and an appropriate comparison.

## How to add a claim

1. **Measure it in a tracked commit.** The command must run from a clean checkout of the commit named in the `Source commit` column. Record the full command, including environment variables, fixture paths and seeds.
2. **Name the dataset or fixture version.** For Kilter-derived data use the manifest id from `ml/datasets/manifests/`; for synthetic fixtures use the fixture file and its commit. Data under `data/` is never committed, so the manifest hash is the reference.
3. **Describe the environment.** OS, CPU, memory, container image or `mise.toml` toolchain, and whether the run was local or CI (with the run URL).
4. **Commit the raw artifact** under `docs/evidence/<ticket>/` as text (`reports/` is gitignored; or link the CI artifact) so a reviewer can recompute the number. A screenshot is not a raw artifact.
5. **Have someone other than the author verify it.** `Verified by` is a human name or a second agent's read-only review, with the date. An agent may not verify its own claim.
6. **Add the row** in the same commit as the artifact, or in a `docs:` commit that references the artifact commit. One row per claim; a re-measurement is a new row, never an edit of the old one.
7. **Label scope precisely.** Synthetic inputs, counterfactual replay ranks and offline metrics must say so in the `Claim` text. A counterfactual rank is not an observed user outcome (AGENTS.md "Replay rigor").

## What may not be entered

- Targets from PRD §11 or `planning/` (for example an SLO such as "p99 under 200 ms") until measured.
- Numbers produced on data whose rights review in `docs/data-source-audit.md` is not PERMITTED for that use.
- Any personalized-outcome claim without real, consented user histories (CLAUDE.md nonnegotiables).
- Any medical, injury or physical-risk claim.

## Checking the README

Until a CI check exists (BC-0015 marks it optional), reviewers run this before accepting a README or pitch change and expect no output:

```bash
grep -nE '[0-9]+(\.[0-9]+)? ?(ms|%|users|routes|rps|req/s)' README.md
```

Any hit must point to a row in this ledger or be rewritten as a plan.
