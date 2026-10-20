#!/usr/bin/env bash
# BC-0307: fault scenario suite for the streaming path (ADR-0004 "Failure cases to test").
# Runs through `make test-chaos [SCENARIOS=a,b] [PARITY=skip]` against the compose stack on
# 127.0.0.1 (started with `make stream-up` + `make stream-submit` when not already running).
#
# Per scenario: a fresh synthetic_ user, the scenario script scripts/chaos/<name>.sh (the fault
# injection and the scenario's own assertions), then the harness's common assertions:
#   1. convergence (test/integration/parity/README.md "settle"): outbox backlog 0, Flink and
#      materializer consumer-group lag 0, the user's STREAM row at cutoff_w >= the last admitted
#      event with the contract checksum re-derived from Postgres at that W (<= 120 s);
#   2. the parity oracle `make test-parity USER=<uuid> OUT=docs/evidence/BC-0307/parity-<name>.json`
#      (exit 0 required, or exit 1 + DIFF when the scenario declares PARITY_MODE=expect-diff);
#   3. no canonical attempt lost: ACTIVE count == expected and every attempt the scenario created
#      exists at its expected final revision and status;
#   4. stream-dlq.v1 grew by exactly what the scenario expects (0 for all but the DLQ scenario).
# Writes docs/evidence/BC-0307/<name>.txt (transcript) and appends one row per scenario to
# docs/evidence/BC-0307/summary.md under a run header with the hardware block. After the suite the
# oracle runs once over ALL users with a STREAM row. Exit 1 if any scenario or the final parity
# failed, 2 for a precondition failure. Docs: test/chaos/README.md.
set -euo pipefail

export CHAOS_SCENARIO=harness
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"
cd "$CHAOS_ROOT"

DEFAULT_SCENARIOS="dup-publish,late-2h-inside-window,late-3d-reconciliation,correction-then-retraction,api-sigkill-during-burst,api-sigkill-after-publish,flink-taskmanager-restart,kafka-restart,same-watermark-correction,invalid-events-to-dlq"
SCENARIOS="${SCENARIOS:-$DEFAULT_SCENARIOS}"
PARITY="${PARITY:-run}"            # run | skip
export PARITY CHAOS_INVOKE CHAOS_BASE_URL CHAOS_FLINK_URL CHAOS_CONVERGE_TIMEOUT_S
RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)"
SUMMARY="$CHAOS_EVIDENCE/summary.md"
HARNESS_LOG="$CHAOS_EVIDENCE/harness.txt"
mkdir -p "$CHAOS_EVIDENCE"
chaos_guard_base_url
chaos_work_dir

# ---------------------------------------------------------------------------------------------
# preconditions

java_bin() { echo "${JAVA_HOME:+$JAVA_HOME/bin/}java"; }

ensure_stack() {
  [ -f .env ] || die ".env missing (copy .env.example and set every value)"
  "$(java_bin)" -version >/dev/null 2>&1 || die "no Java 21 on PATH/JAVA_HOME (export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home)"
  if curl -fsS -o /dev/null --max-time 3 "$CHAOS_BASE_URL/health/ready" 2>/dev/null; then
    log "api is up at $CHAOS_BASE_URL"
  else
    log "api is not up; starting the stream profile"
    if [ "$CHAOS_INVOKE" = make ]; then make stream-up; else [ -f "$CHAOS_API_JAR" ] || die "missing $CHAOS_API_JAR"; compose up -d --build --wait; fi
    wait_for "api healthy" 180 3 bash -c "curl -fsS -o /dev/null --max-time 3 '$CHAOS_BASE_URL/health/ready'" || die "api did not become healthy"
  fi
  # grep reads to EOF (no -q): under pipefail an early exit would hand the producer an EPIPE.
  curl -fsS --max-time 5 "$CHAOS_BASE_URL/prometheus" | grep '^beacon_materializer_applied_total' >/dev/null \
    || die "the api is not running with the stream profile (no materializer meters on /prometheus): run make stream-up"
  compose ps --status running --services | grep -x kafka >/dev/null || die "kafka container is not running (make stream-up)"
  compose ps --status running --services | grep -x taskmanager >/dev/null || die "flink taskmanager is not running (make stream-up)"
  local routes; routes="$(psql_q "SELECT count(*) FROM routes")"
  if [ "${routes:-0}" = 0 ]; then log "no routes in Postgres; running make seed-demo"; make seed-demo; fi
  local job; job="$(flink_running_job)"
  if [ -z "$job" ]; then
    log "no RUNNING Flink job; running make stream-submit"
    make stream-submit
    wait_for "flink job RUNNING" 120 3 bash -c "[ -n \"\$(curl -fsS --max-time 5 '$CHAOS_FLINK_URL/jobs' | python3 -I -c 'import json,sys; print(next((j[\"id\"] for j in json.load(sys.stdin)[\"jobs\"] if j[\"status\"]==\"RUNNING\"), \"\"))')\" ]" || die "stream job did not reach RUNNING"
    job="$(flink_running_job)"
  fi
  flink_wait_running "$job" 120 || die "stream job $job is not RUNNING with every vertex RUNNING"
  log "stream job $job RUNNING, checkpoints completed so far: $(flink_checkpoints_completed "$job")"
  if [ "$PARITY" != skip ]; then
    [ -f scripts/parity/parity.sh ] || die "scripts/parity/parity.sh (BC-0306) is not present; run with PARITY=skip for a mechanics-only run"
  fi
  log "api jar sha256 $(jar_sha | cut -c1-12).. ($(stat -f %Sm -t %Y-%m-%dT%H:%M:%S "$CHAOS_API_JAR")), git $(git rev-parse --short HEAD), CHAOS_INVOKE=$CHAOS_INVOKE PARITY=$PARITY"
  log "accounts created from this address today (cap beacon.auth.register-per-ip-per-day=20 by default): $(psql_q "SELECT count(*) FROM account_creations WHERE created_at >= date_trunc('day', now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC'" 2>/dev/null || echo '?')"
}

hardware_block() {
  local mem; mem="$(sysctl -n hw.memsize 2>/dev/null || echo 0)"
  cat <<EOF
- cpu: $(sysctl -n machdep.cpu.brand_string 2>/dev/null || echo unavailable)
- memory_gib: $(python3 -I -c "print(round(int('$mem')/2**30, 1))")
- os: $(sw_vers -productName 2>/dev/null) $(sw_vers -productVersion 2>/dev/null) $(sw_vers -buildVersion 2>/dev/null)
- docker: $(docker info --format 'engine {{.ServerVersion}}, {{.NCPU}} cpus, {{.MemTotal}} bytes for containers' 2>/dev/null || echo unavailable)
- python: $(python3 --version 2>&1 | cut -d' ' -f2)
- client: this script on the same laptop as the compose stack (loopback); client clock for every timestamp
EOF
}

write_run_header() {
  if [ ! -f "$SUMMARY" ]; then
    cat >"$SUMMARY" <<'EOF'
# BC-0307 — fault scenario suite: measured runs

Every row below was produced by `make test-chaos` (test/chaos/stream/run.sh) against the compose
stream stack on this laptop; nothing here is typed by hand. Per-scenario transcripts are the
`<scenario>.txt` files next to this one, parity reports `parity-<scenario>.json` (the oracle of
BC-0306, scripts/parity/README.md). Scenario semantics and what each assertion proves:
test/chaos/README.md. All users are `synthetic_*` accounts created by the run.
EOF
  fi
  {
    echo
    echo "## Run $RUN_ID (commit $(git rev-parse --short HEAD), PARITY=$PARITY, CHAOS_INVOKE=$CHAOS_INVOKE, scenarios: ${SCENARIOS//,/ })"
    echo
    echo "Hardware:"
    echo
    hardware_block
    echo
    echo "| scenario | result | runtime s | converge s | parity | attempts expected/found | dlq growth | notes |"
    echo "|---|---|---|---|---|---|---|---|"
  } >>"$SUMMARY"
}

# ---------------------------------------------------------------------------------------------
# per-scenario

pred_true() { [ "$(psql_q "$1")" = t ]; }

# Runs the scenario script and the common assertions; writes $CHAOS_WORK/<name>.row. Returns 0
# on PASS, 1 on FAIL. Everything it prints is the transcript.
scenario_steps() { # $1 name, $2 row file, $3 dlq count before
  local name="$1" rowf="$2" dlq_before="$3" result="$CHAOS_WORK/$name.result" status=0
  local parity_cell="skipped" found="?" converge="-" notes=""
  local start; start=$(date +%s)
  write_row() { # $1 verdict
    printf '| %s | %s | %s | %s | %s | %s/%s | %s | %s |\n' "$name" "$1" "$(( $(date +%s) - start ))" "$converge" "$parity_cell" "${EXPECT_ACTIVE:-?}" "$found" "${DLQ_GROWTH:-?}/${EXPECT_DLQ_GROWTH:-?}" "$notes" >"$rowf"
  }
  fail() { log "FAIL: $*"; notes="${notes:+$notes; }$*"; write_row FAIL; return 1; }

  log "=== scenario $name: start $(ts), stream job $(flink_running_job), stream-dlq.v1 records before: $dlq_before ==="
  rm -f "$result"
  if ! CHAOS_RESULT="$result" CHAOS_SCENARIO="$name" CHAOS_WORK="$CHAOS_WORK" bash "$CHAOS_ROOT/scripts/chaos/$name.sh"; then
    fail "scenario script scripts/chaos/$name.sh exited non-zero"; return 1
  fi
  [ -f "$result" ] || { fail "scenario wrote no result file"; return 1; }
  # shellcheck disable=SC1090
  . "$result"
  CHAOS_SCENARIO="$name"
  chaos_guard_synthetic_user "$SCENARIO_USER_ID"
  log "--- harness: convergence ---"
  # shellcheck disable=SC2086
  if chaos_converge "$SCENARIO_USER_ID" "$LAST_OCCURRED_AT" $STREAM_EXCLUDE; then converge="$CONVERGE_S"; else converge="timeout"; fail "convergence not reached within $CHAOS_CONVERGE_TIMEOUT_S s"; return 1; fi

  log "--- harness: parity oracle (mode $PARITY_MODE) ---"
  if [ "$PARITY" = skip ]; then
    parity_cell="skipped (PARITY=skip)"; log "parity skipped (PARITY=skip)"
  else
    local pstatus=0
    chaos_parity_run "$SCENARIO_USER_ID" "$CHAOS_EVIDENCE/parity-$name.json" || pstatus=$?
    case "$PARITY_MODE" in
      zero)
        if [ "$pstatus" = 0 ] && [ "$PARITY_STATUS" = EQUAL ]; then parity_cell="EQUAL (exit 0)"; log "ASSERT PASS parity: exit 0, status EQUAL"
        else parity_cell="$PARITY_STATUS (exit $pstatus)"; fail "parity expected exit 0/EQUAL, got exit $pstatus status ${PARITY_STATUS:-none} diff_keys=$PARITY_DIFF_KEYS checksum_differs=$PARITY_CHECKSUM_DIFFERS"; return 1; fi ;;
      expect-diff)
        if [ "$pstatus" = 1 ] && [ "$PARITY_STATUS" = DIFF ] && [ "$PARITY_CHECKSUM_DIFFERS" = true ]; then parity_cell="DIFF as expected (exit 1, keys $PARITY_DIFF_KEYS)"; log "ASSERT PASS parity: exit 1 with DIFF and checksum_differs=true (the declared expectation)"
        else parity_cell="$PARITY_STATUS (exit $pstatus)"; fail "parity expected exit 1/DIFF, got exit $pstatus status ${PARITY_STATUS:-none}"; return 1; fi ;;
      *) fail "unknown PARITY_MODE '$PARITY_MODE'"; return 1 ;;
    esac
  fi

  log "--- harness: no canonical attempt lost ---"
  found="$(active_attempts "$SCENARIO_USER_ID")"
  [ "$found" = "$EXPECT_ACTIVE" ] && log "ASSERT PASS ACTIVE attempts: $found == $EXPECT_ACTIVE" || { fail "ACTIVE attempts: expected $EXPECT_ACTIVE, found $found"; return 1; }
  local spec id rev st actual n=0
  for spec in $EXPECT_ATTEMPTS; do
    id="${spec%%:*}"; rev="$(echo "$spec" | cut -d: -f2)"; st="$(echo "$spec" | cut -d: -f3)"
    actual="$(attempt_rev_status "$id")"
    [ "$actual" = "$rev:$st" ] || { fail "attempt $id: expected revision $rev status $st, found '${actual:-missing}'"; return 1; }
    n=$((n + 1))
  done
  log "ASSERT PASS every expected attempt row present at its final revision/status ($n rows checked)"

  log "--- harness: DLQ growth ---"
  local dlq_after; dlq_after="$(topic_end_offsets_sum stream-dlq.v1)"
  DLQ_GROWTH=$((dlq_after - dlq_before))
  [ "$DLQ_GROWTH" = "${EXPECT_DLQ_GROWTH:-0}" ] && log "ASSERT PASS stream-dlq.v1 growth: $DLQ_GROWTH == ${EXPECT_DLQ_GROWTH:-0} ($dlq_before -> $dlq_after)" || { fail "stream-dlq.v1 grew by $DLQ_GROWTH, expected ${EXPECT_DLQ_GROWTH:-0}"; return 1; }

  log "=== scenario $name: PASS in $(( $(date +%s) - start )) s ==="
  write_row PASS
  return 0
}

run_scenario() { # $1 name
  local name="$1" transcript="$CHAOS_EVIDENCE/$1.txt" rowf="$CHAOS_WORK/$1.row" status=0 dlq_before
  [ -f "$CHAOS_ROOT/scripts/chaos/$name.sh" ] || { log "unknown scenario '$name' (no scripts/chaos/$name.sh)"; echo "| $name | FAIL | 0 | - | - | ?/? | ? | no such scenario |" >"$rowf"; return 1; }
  dlq_before="$(topic_end_offsets_sum stream-dlq.v1)"
  { echo "# BC-0307 chaos transcript: $name, run $RUN_ID, $(ts), commit $(git rev-parse --short HEAD)"; } >"$transcript"
  set +e
  { scenario_steps "$name" "$rowf" "$dlq_before"; } 2>&1 | tee -a "$transcript"
  status=${PIPESTATUS[0]}
  set -e
  [ -f "$rowf" ] || echo "| $name | FAIL | ? | - | - | ?/? | ? | harness aborted before writing a row |" >"$rowf"
  cat "$rowf" >>"$SUMMARY"
  return "$status"
}

# ---------------------------------------------------------------------------------------------
# main

main() {
  local failures=0 names name started
  started=$(date +%s)
  log "=== BC-0307 chaos run $RUN_ID: scenarios ${SCENARIOS//,/ } (PARITY=$PARITY, CHAOS_INVOKE=$CHAOS_INVOKE) ==="
  ensure_stack
  write_run_header
  IFS=',' read -r -a names <<<"$SCENARIOS"
  for name in "${names[@]}"; do
    name="$(echo "$name" | tr -d ' ')"; [ -n "$name" ] || continue
    if run_scenario "$name"; then log "scenario $name PASS"; else log "scenario $name FAIL"; failures=$((failures + 1)); fi
  done

  log "--- final parity over every user with a STREAM row ---"
  if [ "$PARITY" = skip ]; then
    log "final all-users parity skipped (PARITY=skip)"
    echo "| all-users parity | skipped | - | - | skipped (PARITY=skip) | - | - | mechanics only |" >>"$SUMMARY"
  else
    local pstatus=0 line
    if [ "$CHAOS_INVOKE" = make ]; then run_make test-parity USER=all OUT="$CHAOS_EVIDENCE/parity-all-users.json" || pstatus=$?
    else bash scripts/parity/parity.sh --user all --out "$CHAOS_EVIDENCE/parity-all-users.json" || pstatus=$?; fi
    line="$(python3 -I -c 'import json,sys; r=json.load(open(sys.argv[1])); print("users=%d with_stream_row=%d compared=%d equal=%d diffs=%d same_w_mismatch=%d" % (r["users_total"], r["users_with_stream_row"], r["compared"], r["equal"], r["diffs"], r["same_w_mismatch"]))' "$CHAOS_EVIDENCE/parity-all-users.json" 2>/dev/null || echo "no report")"
    # make reports a failed recipe as 2; a written report with diffs means the oracle exited 1.
    if [ "$CHAOS_INVOKE" = make ] && [ "$pstatus" = 2 ] && [ -f "$CHAOS_EVIDENCE/parity-all-users.json" ] && echo "$line" | grep -vq "diffs=0 same_w_mismatch=0"; then pstatus=1; fi
    if [ "$pstatus" = 0 ]; then log "ASSERT PASS all-users parity exit 0: $line"; echo "| all-users parity | PASS | - | - | exit 0 | - | - | $line |" >>"$SUMMARY"
    else log "FAIL all-users parity exit $pstatus: $line"; echo "| all-users parity | FAIL | - | - | exit $pstatus | - | - | $line |" >>"$SUMMARY"; failures=$((failures + 1)); fi
  fi

  log "--- docker stats --no-stream ---"
  docker stats --no-stream --format 'table {{.Name}}\t{{.MemUsage}}\t{{.MemPerc}}\t{{.CPUPerc}}' | tee "$CHAOS_EVIDENCE/docker-stats.txt"
  {
    echo
    echo "Run $RUN_ID finished $(ts): $(( $(date +%s) - started )) s wall-clock, $failures failure(s). docker stats: docker-stats.txt."
  } >>"$SUMMARY"
  log "=== run $RUN_ID done in $(( $(date +%s) - started )) s, failures: $failures; summary: $SUMMARY ==="
  [ "$failures" = 0 ]
}

main "$@" 2>&1 | tee "$HARNESS_LOG"
exit "${PIPESTATUS[0]}"
