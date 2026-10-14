# Evidence ledger

This ledger is the only place where a measured result about Beacon Climb may be recorded. The README, pitch material, model cards and handoffs may cite a number only if it appears here with a source commit, the command that produced it and a raw artifact. PRD §18 defines the policy; this file is its table.

**Status (2026-10-14): zero claims.** Nothing has been measured. Every performance, dataset, model, replay, experiment-safety and product-validation number that appears anywhere in `planning/` is a target or an illustration, not a result.

## Ledger

| Claim | Source commit | Measurement command | Dataset/version | Environment | Raw artifact | Verified by |
|---|---|---|---|---|---|---|
| _(none yet)_ | | | | | | |

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
4. **Commit the raw artifact** under `reports/` (or link the CI artifact) so a reviewer can recompute the number. A screenshot is not a raw artifact.
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
