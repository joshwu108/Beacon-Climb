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

# ---------- 1. night mode (only for the session that received the overnight prompt) ----------
NIGHT_FLAG="planning/handoffs/NIGHT-ACTIVE"
# Newest night log wins (dated file names sort chronologically); older nights already carry a
# Morning summary and must not release a later night's session early.
NIGHT_LOG="$(ls planning/handoffs/NIGHT-*.md 2>/dev/null | grep -v DONE | sort | tail -1)"
NIGHT_CAP=80
# Identify the overnight session: its transcript has a USER message containing the prompt's
# opening phrase. Other sessions in the same repo (reviews, chat) must not be held open.
is_night_owner="$(printf '%s' "$input" | python3 -I -c '
import sys, json
try:
    d = json.load(sys.stdin); tp = d.get("transcript_path") or ""
    marker = "sole engineer on Beacon Climb tonight"
    found = False
    with open(tp, encoding="utf-8", errors="ignore") as f:
        for line in f:
            if marker not in line: continue
            try: obj = json.loads(line)
            except Exception: continue
            if obj.get("type") == "user":
                msg = obj.get("message", {}); content = msg.get("content", "")
                text = content if isinstance(content, str) else json.dumps(content)
                if marker in text and "tool_result" not in text[:200]:
                    found = True; break
    print("yes" if found else "no")
except Exception:
    print("no")')"
session_id="$(printf '%s' "$input" | python3 -I -c 'import sys,json
try: print(json.load(sys.stdin).get("session_id","nosession"))
except Exception: print("nosession")')"
NIGHT_COUNTER="planning/handoffs/.night-stop-count-${session_id}"
if [ -f "$NIGHT_FLAG" ] && [ "$is_night_owner" = "yes" ]; then
  if [ -n "$NIGHT_LOG" ] && grep -qi '^## *Morning summary' "$NIGHT_LOG"; then
    rm -f "$NIGHT_COUNTER"
  else
    n=0; [ -f "$NIGHT_COUNTER" ] && n="$(cat "$NIGHT_COUNTER" 2>/dev/null || echo 0)"
    n=$((n + 1)); printf '%s' "$n" > "$NIGHT_COUNTER"
    if [ "$n" -le "$NIGHT_CAP" ]; then
      dirty="$(git status --porcelain --untracked-files=all 2>/dev/null | wc -l | tr -d ' ')"
      block "NIGHT MODE (stop $n of $NIGHT_CAP): the unattended run is not finished. If the ticket you just worked on is complete, commit its paths with the commit skill and log it in the NIGHT log; if it is blocked after 3 genuine attempts, write its BLOCKED handoff and log it. Then start the NEXT ticket in the list from the overnight prompt you were given (the newest planning/*-overnight-prompt*.md). Do not ask questions; nobody is here. Only stop for good after writing the '## Morning summary' section of $NIGHT_LOG (the prompt's final step). Working tree currently has $dirty uncommitted paths."
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
