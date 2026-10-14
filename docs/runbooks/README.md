# Runbooks

Operational procedures for running, diagnosing and recovering Beacon Climb. None exist yet: the repository is a scaffold with no catalog, accounts, attempts or recommendations (see `README.md`). This index lists the runbooks each epic is expected to add, so that a feature is not called done without its operating procedure (PRD FR-11; consolidated under BC-0604).

## Index

| Runbook | Owner epic | Status |
|---|---|---|
| Local development: `make dev`, `make down`, Docker prerequisites | E00 scaffold (BC-0009) | planned, see `README.md` quick start |
| Database migrations and rollback (Flyway) | E01 | planned |
| Demo seed and reset (`make seed-demo`) | E01 (BC-0120) | planned |
| Kilter import (local only, rights-gated) | E01 (BC-0105) | planned, blocked on `docs/data-source-audit.md` |
| Erasure request end to end (30-day grace, all stores) | E04 (BC-0412) | planned, policy in `docs/privacy-and-retention.md` |
| Kafka and Flink: restart, checkpoint recovery, DLQ drain | E03 | planned |
| Model rollout, canary and rollback | E04 | planned |
| Replay a decision and compare | E04 | planned |
| Incident response and postmortem template | E06 | planned |

## Writing a runbook

Create `docs/runbooks/<kebab-case-name>.md` with these sections, in order:

1. **Purpose and trigger.** When an operator opens this runbook (alert name, symptom, scheduled task).
2. **Preconditions.** Required access, tools from `mise.toml`, and the commit or release the procedure was last verified against.
3. **Steps.** Numbered. Every step is a command in a fenced block followed by the expected output. No step may say "verify it works" without showing what success looks like.
4. **Verification.** The command that proves the system is healthy afterwards.
5. **Rollback.** How to undo the procedure if a step fails.
6. **Evidence.** Date and commit of the last real run, with the output pasted or linked. A runbook that has never been executed says so at the top.

Runbooks never contain credentials, real user identifiers or attempt notes. Use the pseudonymous ids described in `docs/privacy-and-retention.md`.
