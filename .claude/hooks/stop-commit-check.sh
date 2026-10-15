#!/bin/bash
# Stop hook with two jobs:
#  1. Night mode: while planning/handoffs/NIGHT-ACTIVE exists and the night log has no
#     "Morning summary" heading, block every stop with "continue" (hard cap via counter)
#     so an unattended run keeps working through the ticket list.
#  2. Commit reminder: if the task just finished left uncommitted changes, send Claude back
#     to commit them with the project `commit` skill (owner identity, no attribution).
# Exit 0 always; decisions are returned as JSON on stdout.

input="$(cat)"
active="$(printf '%s' "$input" | python3 -I -c 'import sys,json
try: print(json.load(sys.stdin).get("stop_hook_active", False))
except Exception: print(False)')"

root="$(git rev-parse --show-toplevel 2>/dev/null)" || exit 0
cd "$root" || exit 0

block() {
  printf '{"decision":"block","reason":%s}\n' "$(printf '%s' "$1" | python3 -I -c 'import sys,json; print(json.dumps(sys.stdin.read()))')"
  exit 0
}

# ---------- 1. night mode ----------
NIGHT_FLAG="planning/handoffs/NIGHT-ACTIVE"
NIGHT_LOG="$(ls planning/handoffs/NIGHT-*.md 2>/dev/null | grep -v DONE | head -1)"
NIGHT_COUNTER="planning/handoffs/.night-stop-count"
NIGHT_CAP=80
if [ -f "$NIGHT_FLAG" ]; then
  if [ -n "$NIGHT_LOG" ] && grep -qi '^## *Morning summary' "$NIGHT_LOG"; then
    rm -f "$NIGHT_COUNTER"
  else
    n=0; [ -f "$NIGHT_COUNTER" ] && n="$(cat "$NIGHT_COUNTER" 2>/dev/null || echo 0)"
    n=$((n + 1)); printf '%s' "$n" > "$NIGHT_COUNTER"
    if [ "$n" -le "$NIGHT_CAP" ]; then
      dirty="$(git status --porcelain --untracked-files=all 2>/dev/null | wc -l | tr -d ' ')"
      block "NIGHT MODE (stop $n of $NIGHT_CAP): the unattended run is not finished. If the ticket you just worked on is complete, commit its paths with the commit skill and log it in the NIGHT log; if it is blocked after 3 genuine attempts, write its BLOCKED handoff and log it. Then start the NEXT ticket in the list from planning/11-overnight-prompt.md. Do not ask questions; nobody is here. Only stop for good after writing the '## Morning summary' section of the NIGHT log (final step 28). Working tree currently has $dirty uncommitted paths."
    fi
    # cap reached: fall through and allow the stop
  fi
fi

# ---------- 2. commit reminder (once per stop) ----------
[ "$active" = "True" ] && exit 0
if [ -n "$(git status --porcelain --untracked-files=all 2>/dev/null)" ]; then
  changed="$(git status --porcelain --untracked-files=all | wc -l | tr -d ' ')"
  block "Working tree has $changed uncommitted paths. If the task you just finished changed tracked files and is complete, commit ONLY that task's paths now with the project commit skill (owner identity, conventional message, no attribution; stage paths explicitly, never -A). Also commit any completed Codex ticket that has a planning/handoffs/BC-####.md handoff and no commit yet, one commit per ticket, per the skill's 'on behalf of other agents' rules. If none of the dirty paths are yours and no handoff is ready, reply 'nothing to commit for this task' and stop. Never commit half-finished work or while waiting on the user."
fi
exit 0
