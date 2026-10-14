#!/bin/bash
# Stop hook: if the task just finished left uncommitted changes, send Claude back
# to commit them with the project `commit` skill (owner identity, no attribution).
# Fires once per stop (stop_hook_active guards against loops). Exit 0 always.
# The working tree is shared with Codex agents, so the reminder is scoped to
# "your own task's paths" and must not trigger commits of other agents' work.

input="$(cat)"
active="$(printf '%s' "$input" | python3 -I -c 'import sys,json
try: print(json.load(sys.stdin).get("stop_hook_active", False))
except Exception: print(False)')"
[ "$active" = "True" ] && exit 0

root="$(git rev-parse --show-toplevel 2>/dev/null)" || exit 0
cd "$root" || exit 0

if [ -n "$(git status --porcelain --untracked-files=all 2>/dev/null)" ]; then
  changed="$(git status --porcelain --untracked-files=all | wc -l | tr -d ' ')"
  reason="Working tree has $changed uncommitted paths. If the task you just finished changed tracked files and is complete, commit ONLY that task's paths now with the project commit skill (owner identity, conventional message, no attribution; stage paths explicitly, never -A). Also commit any completed Codex ticket that has a planning/handoffs/BC-####.md handoff and no commit yet, one commit per ticket, per the skill's 'on behalf of other agents' rules. If none of the dirty paths are yours and no handoff is ready, reply 'nothing to commit for this task' and stop. Never commit half-finished work or while waiting on the user."
  printf '{"decision":"block","reason":%s}\n' "$(printf '%s' "$reason" | python3 -I -c 'import sys,json; print(json.dumps(sys.stdin.read()))')"
fi
exit 0
