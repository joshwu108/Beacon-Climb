---
name: commit
description: Commit the current working tree as the repo owner (Joshua Wu) with a conventional-commit message and no co-author or tool attribution lines. Use automatically at the end of every completed task (ticket, subtask, or user request that changed files), and whenever the user types /commit or asks to commit.
---

# /commit — commit as the human, cleanly

Arguments: `$ARGUMENTS` (optional). May be a full message, a scope hint, or a pathspec list. If empty, derive everything from the diff.

## When this runs
1. **Automatically, once per completed task.** The user has given standing permission (2026-10-14): after you finish a task that changed tracked files, commit it before reporting. One task = one commit; do not batch unrelated tickets. The Stop hook enforces this if you forget.
2. **On request:** `/commit`, "commit this", "commit as docs:", etc.
3. **Never** mid-task, never when you stopped to ask the user something, never for half-finished work. In those cases say "task incomplete, not committing" and stop. Subagents never commit; the top-level session commits after a subagent returns.

## Hard rules
- Author and committer must be the repo owner. Never pass `--author`. The message must contain no attribution trailer: reject it if any line matches `^(Co-Authored-By|Signed-off-by):` or contains `Generated with`, or if it credits an AI tool for the work. File names such as `CLAUDE.md` or `.claude/` are fine. The message describes the change only.
- **Stage only this task's paths.** Other agents (Codex terminals, subagents in worktrees) may have uncommitted work in the same tree. Never `git add -A` when `git status` shows paths outside the task's allowed write paths; add the task's paths explicitly and leave the rest untracked. If you cannot tell which files belong to the task, stop and ask.
- Never `git push`, `git tag`, `git merge`, `git rebase`, `git reset`, `git stash`, `git clean`, amend, or rewrite history. One new commit on the current branch, nothing else.
- Never commit secrets, `*.sqlite*`, anything under `data/`, `planning/` (gitignored anyway) or files larger than 1 MB. If any are staged, unstage them and tell the user.
- Do not use `--no-verify`. If a pre-commit hook fails, fix the formatting it reports if trivial, otherwise stop and report.

## Committing on behalf of other agents (Codex terminals)
Codex agents never commit. A ticket is **complete** when `planning/handoffs/BC-####.md` exists (a `.log` alone is not a handoff). For each complete ticket not yet in history (`git log --oneline --grep 'BC-####'` is empty):
1. Read the handoff; take its "changed paths" list. Verify with `git status --porcelain -- <paths>` that those paths are dirty and that nothing in the list is outside the ticket's allowed paths from `planning/01-backlog.csv`.
2. Skip the ticket (do not commit) if any listed file was modified in the last 2 minutes (`find <path> -mmin -2`) — the agent may still be writing — or if the handoff says tests failed and the human has not accepted that.
3. Stage exactly those paths, run the safety scan, and commit as `<type>: BC-#### <short title>` with a body summarizing the handoff (tests run and results, caveats). One ticket, one commit, in ticket order.
4. Record in `planning/handoffs/COMMITS.md`: ticket, hash, timestamp, anything skipped and why.
Never commit a ticket whose handoff is missing, even if its files look finished.

## Steps
1. Verify identity. Run:
   ```bash
   git config user.name; git config user.email
   ```
   Expected `Joshua Wu` and `joshwu10806@gmail.com`. If either differs or is empty, stop and tell the user the exact `git config` command to fix it. Do not guess an identity.
2. Inspect: `git status --short` and `git diff --stat` (and `git diff --cached --stat` if something is already staged). If there is nothing to commit, say so and stop.
3. Stage. If `$ARGUMENTS` contains pathspecs, `git add -- <paths>`; otherwise `git add -A`. Then run the safety check and unstage offenders:
   ```bash
   git diff --cached --name-only | grep -Ei '(^data/|\.sqlite|\.env$|\.env\.|\.pem$|\.key$|id_rsa)' || true
   git diff --cached --name-only | xargs -I{} sh -c 'test -f "{}" && test $(wc -c < "{}") -gt 1048576 && echo "{} > 1MB"' || true
   ```
   Also grep the staged diff for obvious secrets: `git diff --cached | grep -Ei '(api[_-]?key|secret|token|password)\s*[:=]\s*["\x27][^"\x27]{8,}' || true`. Unstage and report anything found.
4. Write the message to a temp file. Format from `AGENTS.md`/rules: `<type>: <description>` with type in feat, fix, refactor, docs, test, chore, perf, ci; imperative mood; ≤ 72 chars subject; optional body with bullet points of what changed and why; reference ticket IDs (`BC-####`) when the diff maps to one. If `$ARGUMENTS` is a full message, use it verbatim after checking it contains no attribution line.
5. Commit: `git commit -F <tempfile>`. Then show the user `git log -1 --format='%h %an <%ae>%n%B'` so they can see the author and the message. Confirm there is no trailer.
6. Report: hash, author line, files changed count. Do not push. Do not offer to push unless asked.
