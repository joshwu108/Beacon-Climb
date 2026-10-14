#!/bin/bash
# Stop hook: if the task just finished left uncommitted changes, send Claude back
# to commit them with the project `commit` skill (owner identity, no attribution).
# Fires once per stop (stop_hook_active guards against loops). Exit 0 always.

input="$(cat)"
active="$(printf '%s' "$input" | python3 -I -c 'import sys,json
try: print(json.load(sys.stdin).get("stop_hook_active", False))
except Exception: print(False)')"
[ "$active" = "True" ] && exit 0

root="$(git rev-parse --show-toplevel 2>/dev/null)" || exit 0
cd "$root" || exit 0

if [ -n "$(git status --porcelain --untracked-files=all 2>/dev/null)" ]; then
  changed="$(git status --porcelain --untracked-files=all | wc -l | tr -d ' ')"
  reason="Uncommitted changes exist ($changed paths). If the task you just finished is complete, commit them now with the project commit skill: owner identity, conventional '<type>: <description>' message describing this task, no co-author or tool attribution, nothing under data/ or secrets. If the task is NOT complete, or you stopped to ask the user a question, do not commit: say 'task incomplete, not committing' and stop."
  printf '{"decision":"block","reason":%s}\n' "$(printf '%s' "$reason" | python3 -I -c 'import sys,json; print(json.dumps(sys.stdin.read()))')"
fi
exit 0
