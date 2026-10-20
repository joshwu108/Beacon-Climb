#!/usr/bin/env bash
# BC-0307: shared functions of the fault scenario suite (test/chaos/stream/run.sh and every
# scripts/chaos/<scenario>.sh source this file). Everything here talks to the compose stack on
# 127.0.0.1 only, through the api (test/chaos/stream/api.py), psql in the postgres container, the
# Kafka CLI in the kafka container and the Flink REST API; synthetic users only (`synthetic_*`).
#
# Convention: functions named chaos_* are the scenario-facing API, everything else is internal.
# Every log line carries a UTC timestamp. No token is ever printed; token files live in a 0700
# work directory that the harness (or a standalone scenario) removes on exit.
#
# Environment knobs (all optional):
#   CHAOS_BASE_URL   api base URL, must be http://127.0.0.1:<port> (default http://127.0.0.1:8080)
#   CHAOS_FLINK_URL  Flink REST (default http://127.0.0.1:8081)
#   CHAOS_INVOKE     make (default) runs `make test-parity|reconcile|recompute-features|dlq-redrive`
#                    exactly as documented; direct runs the same jar profiles without the Gradle
#                    rebuild those targets perform (the rebuild rewrites the jar bind-mounted into
#                    the running api container; see test/chaos/README.md "Jar hazard")
#   CHAOS_CONVERGE_TIMEOUT_S  bound of the convergence wait (default 120)
#   CHAOS_WORK       0700 directory for token files (created when unset)
#   CHAOS_RESULT     path of the KEY=VALUE result file a scenario writes for the harness

[ -n "${CHAOS_LIB_LOADED:-}" ] && return 0
CHAOS_LIB_LOADED=1

CHAOS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CHAOS_ROOT="$(cd "$CHAOS_DIR/../../.." && pwd)"
CHAOS_BASE_URL="${CHAOS_BASE_URL:-http://127.0.0.1:8080}"
CHAOS_FLINK_URL="${CHAOS_FLINK_URL:-http://127.0.0.1:8081}"
CHAOS_INVOKE="${CHAOS_INVOKE:-make}"
CHAOS_CONVERGE_TIMEOUT_S="${CHAOS_CONVERGE_TIMEOUT_S:-120}"
CHAOS_EVIDENCE="$CHAOS_ROOT/docs/evidence/BC-0307"
CHAOS_SCENARIO="${CHAOS_SCENARIO:-chaos}"
CHAOS_API_JAR="$CHAOS_ROOT/services/api/build/libs/api-0.1.0.jar"
CHAOS_DB_URL="jdbc:postgresql://127.0.0.1:5432/beacon"
EMPTY_SET_CHECKSUM="e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
KAFKA_CLI_HEAP="-Xmx160m -Xms32m"

COMPOSE=(docker compose --env-file "$CHAOS_ROOT/.env"
  -f "$CHAOS_ROOT/infra/compose/docker-compose.yml"
  -f "$CHAOS_ROOT/infra/compose/kafka.yml"
  -f "$CHAOS_ROOT/infra/compose/flink.yml"
  --profile core --profile stream)

# ---------------------------------------------------------------------------------------------
# logging and guards

ts() { date -u +%Y-%m-%dT%H:%M:%SZ; }
log() { printf '[%s] %s: %s\n' "$(ts)" "$CHAOS_SCENARIO" "$*"; }
die() { printf '[%s] %s: FAIL: %s\n' "$(ts)" "$CHAOS_SCENARIO" "$*" >&2; exit 1; }
assert_eq() { # $1 label, $2 expected, $3 actual
  if [ "$2" = "$3" ]; then log "ASSERT PASS $1: $3"; else die "ASSERT $1: expected '$2', got '$3'"; fi
}
assert_ge() { # $1 label, $2 min, $3 actual (integers)
  if [ "$3" -ge "$2" ]; then log "ASSERT PASS $1: $3 >= $2"; else die "ASSERT $1: expected >= $2, got '$3'"; fi
}
assert_ne() {
  if [ "$2" != "$3" ]; then log "ASSERT PASS $1: '$3' != '$2'"; else die "ASSERT $1: expected a value other than '$2'"; fi
}

chaos_guard_base_url() {
  case "$CHAOS_BASE_URL" in
    http://127.0.0.1:*|http://127.0.0.1) ;;
    *) die "refusing CHAOS_BASE_URL='$CHAOS_BASE_URL': the chaos suite only ever runs against 127.0.0.1" ;;
  esac
  case "$CHAOS_FLINK_URL" in
    http://127.0.0.1:*) ;;
    *) die "refusing CHAOS_FLINK_URL='$CHAOS_FLINK_URL': 127.0.0.1 only" ;;
  esac
}

# Refuses any user id that is not a synthetic_ account (checked in Postgres, not trusted from input).
chaos_guard_synthetic_user() { # $1 user id
  local name
  name="$(psql_q "SELECT username FROM users WHERE id = '$1'")"
  case "$name" in
    synthetic_*) ;;
    *) die "refusing to touch user $1 (username '${name:-<unknown>}' is not synthetic_*)" ;;
  esac
}

chaos_work_dir() {
  if [ -z "${CHAOS_WORK:-}" ]; then
    CHAOS_WORK="$(mktemp -d "${TMPDIR:-/tmp}/beacon-chaos.XXXXXX")"
    chmod 700 "$CHAOS_WORK"
    export CHAOS_WORK
    CHAOS_WORK_OWNED=1
    trap 'rm -rf "$CHAOS_WORK"' EXIT
  fi
}

# ---------------------------------------------------------------------------------------------
# stack access

compose() { "${COMPOSE[@]}" "$@"; }

psql_q() { # $1 SQL -> rows as text, tuples only, | separated
  compose exec -T postgres psql -U beacon -d beacon -v ON_ERROR_STOP=1 -tA -c "$1"
}

api() { python3 -I "$CHAOS_DIR/api.py" --base-url "$CHAOS_BASE_URL" "$@"; }

json_get() { # stdin JSON, $1 key -> value (None -> empty)
  python3 -I -c 'import json,sys; v=json.load(sys.stdin).get(sys.argv[1]); print("" if v is None else v)' "$1"
}

flink() { curl -fsS --max-time 10 "$CHAOS_FLINK_URL$1"; }

prometheus_gauge() { # $1 metric name (no labels) -> first sample value or empty
  # awk reads to EOF on purpose: an early `exit` would hand curl an EPIPE and fail the pipeline.
  curl -fsS --max-time 10 "$CHAOS_BASE_URL/prometheus" | awk -v m="$1" '$1 == m && !done { print $2; done = 1 }'
}

kafka_cli() { # $1 tool name, rest args; small heap so the CLI JVM fits the broker's 1 GiB cap
  compose exec -T -e KAFKA_HEAP_OPTS="$KAFKA_CLI_HEAP" kafka "/opt/kafka/bin/$1" --bootstrap-server kafka:9092 "${@:2}"
}

# Sum of a consumer group's lag over attempts.v1 / climber-features.v1 / stream-*.v1. A
# partition the group never committed counts its LOG-END-OFFSET as lag. Prints -1 when the
# group cannot be described (broker down).
group_lag() { # $1 group id
  local out
  if ! out="$(kafka_cli kafka-consumer-groups.sh --describe --group "$1" 2>/dev/null)"; then echo -1; return 0; fi
  printf '%s\n' "$out" | awk '
    $1 == "GROUP" { next }
    NF >= 6 && $2 ~ /\.v1$/ {
      cur = $4; end = $5; lag = $6
      if (lag ~ /^[0-9]+$/) total += lag
      else if (end ~ /^[0-9]+$/ && cur !~ /^[0-9]+$/) total += end
    }
    END { print total + 0 }'
}

topic_end_offsets_sum() { # $1 topic -> total records ever appended (sum of end offsets)
  kafka_cli kafka-get-offsets.sh --topic "$1" --time latest 2>/dev/null | awk -F: '{ s += $3 } END { print s + 0 }'
}

# Prints "partition<TAB>key<TAB>value" for every record on a topic from the beginning (the
# console consumer exits non-zero on its own timeout; that is expected).
consume_all() { # $1 topic, $2 timeout ms
  kafka_cli kafka-console-consumer.sh --topic "$1" --from-beginning --timeout-ms "$2" \
    --property print.partition=true --property print.key=true --property print.headers=true 2>/dev/null || true
}

produce_keyed() { # $1 topic; stdin "key|json" lines
  kafka_cli kafka-console-producer.sh --topic "$1" --property parse.key=true --property key.separator='|' >/dev/null
}

# ---------------------------------------------------------------------------------------------
# Flink

flink_running_job() { flink /jobs | python3 -I -c 'import json,sys; print(next((j["id"] for j in json.load(sys.stdin)["jobs"] if j["status"]=="RUNNING"), ""))'; }
flink_job_state() { flink "/jobs/$1" | json_get state; }
flink_checkpoints_completed() { flink "/jobs/$1/checkpoints" | python3 -I -c 'import json,sys; print(json.load(sys.stdin)["counts"]["completed"])'; }
flink_restored_id() { flink "/jobs/$1/checkpoints" | python3 -I -c 'import json,sys; r=json.load(sys.stdin)["latest"].get("restored"); print("" if not r else r["id"])'; }
flink_all_vertices_running() { flink "/jobs/$1" | python3 -I -c 'import json,sys; print("true" if all(v["status"]=="RUNNING" for v in json.load(sys.stdin)["vertices"]) else "false")'; }

# Sum of one operator counter over every vertex/subtask (e.g. attempts_stale_revision); empty when
# the metric is not exposed under that name.
flink_counter() { # $1 job id, $2 counter name
  local vid names total=0 found=0 v
  for vid in $(flink "/jobs/$1" | python3 -I -c 'import json,sys; print(" ".join(v["id"] for v in json.load(sys.stdin)["vertices"]))'); do
    names="$(flink "/jobs/$1/vertices/$vid/metrics" | python3 -I -c 'import json,sys; n=sys.argv[1]; print(",".join(m["id"] for m in json.load(sys.stdin) if m["id"].endswith("."+n) or m["id"]==n))' "$2")"
    [ -n "$names" ] || continue
    for v in $(flink "/jobs/$1/vertices/$vid/metrics?get=$names" | python3 -I -c 'import json,sys; print(" ".join(str(m["value"]) for m in json.load(sys.stdin)))'); do
      total=$((total + v)); found=1
    done
  done
  [ "$found" = 1 ] && echo "$total" || echo ""
}

wait_for() { # $1 label, $2 timeout s, $3 interval s, rest: command that must return 0
  local label="$1" timeout="$2" interval="$3" start now
  shift 3
  start=$(date +%s)
  while true; do
    if "$@"; then log "wait '$label' satisfied after $(( $(date +%s) - start )) s"; return 0; fi
    now=$(date +%s)
    if [ $((now - start)) -ge "$timeout" ]; then log "wait '$label' TIMED OUT after $timeout s"; return 1; fi
    sleep "$interval"
  done
}

flink_wait_running() { # $1 job id, $2 timeout s; RUNNING with every vertex RUNNING
  wait_for "flink job $1 RUNNING" "$2" 3 bash -c "[ \"\$(curl -fsS --max-time 5 '$CHAOS_FLINK_URL/jobs/$1' | python3 -I -c 'import json,sys; j=json.load(sys.stdin); print(\"ok\" if j[\"state\"]==\"RUNNING\" and all(v[\"status\"]==\"RUNNING\" for v in j[\"vertices\"]) else \"no\")')\" = ok ]"
}

# ---------------------------------------------------------------------------------------------
# rows

stream_row() { # $1 user -> cutoff_w|checksum|partition|offset|feature_as_of|aggregates (empty when none)
  psql_q "SELECT to_char(cutoff_w AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.MS\"Z\"'), provenance_checksum, source_partition, source_offset, coalesce(to_char(feature_as_of AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.MS\"Z\"'), 'null'), aggregates_json::text FROM user_feature_snapshots WHERE user_id = '$1' AND feature_schema_version = 1 AND source = 'STREAM'"
}
sync_row() { # $1 user -> cutoff_w|checksum|feature_as_of|aggregates
  psql_q "SELECT to_char(cutoff_w AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.MS\"Z\"'), provenance_checksum, coalesce(to_char(feature_as_of AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.MS\"Z\"'), 'null'), aggregates_json::text FROM user_feature_snapshots WHERE user_id = '$1' AND feature_schema_version = 1 AND source = 'SYNC'"
}
stream_field() { stream_row "$1" | cut -d'|' -f"$2"; }
sync_field() { sync_row "$1" | cut -d'|' -f"$2"; }

# The contract checksum (climber-features.v1.md section 6) re-derived in SQL over the canonical
# attempts: ACTIVE rows with occurred_at <= W, "<attempt_id>:<revision>" sorted in byte order
# (COLLATE "C" == String.compareTo for ASCII), joined by ",", sha256 hex; the empty set hashes "".
expected_checksum() { # $1 user, $2 W (RFC 3339) or "all", $3.. attempt ids to exclude
  local user="$1" w="$2" where id
  where="status = 'ACTIVE' AND user_id = '$user'"
  shift 2
  [ "$w" != all ] && where="$where AND occurred_at <= '$w'::timestamptz"
  for id in "$@"; do where="$where AND attempt_id <> '$id'"; done
  psql_q "SELECT coalesce(encode(sha256(convert_to(string_agg(attempt_id::text || ':' || revision::text, ',' ORDER BY (attempt_id::text || ':' || revision::text) COLLATE \"C\"), 'UTF8')), 'hex'), '$EMPTY_SET_CHECKSUM') FROM attempts WHERE $where"
}

active_attempts() { psql_q "SELECT count(*) FROM attempts WHERE user_id = '$1' AND status = 'ACTIVE'"; }
attempt_rev_status() { psql_q "SELECT revision || ':' || status FROM attempts WHERE attempt_id = '$1'"; }
outbox_backlog() { psql_q "SELECT count(*) FROM attempt_event_outbox WHERE published_at IS NULL"; }
outbox_backlog_user() { psql_q "SELECT count(*) FROM attempt_event_outbox WHERE published_at IS NULL AND payload_json->>'user_id' = '$1'"; }
reconciliation_rows() { # $1 user -> id|attempt_id|revision|event_type|reason|resolution|resolved
  psql_q "SELECT id, attempt_id, attempt_revision, event_type, reason, coalesce(resolution,'OPEN'), (resolved_at IS NOT NULL) FROM reconciliation_events WHERE user_id = '$1' ORDER BY received_at"
}

# ---------------------------------------------------------------------------------------------
# scenario-facing helpers

# Creates the scenario's fresh synthetic user: USERNAME, USER_ID, TOKEN_FILE, ROUTES (space list).
chaos_new_user() { # $1 short tag (a-z0-9, <= 9 chars)
  chaos_work_dir
  local tag="$1" hex
  hex="$(python3 -I -c 'import secrets; print(secrets.token_hex(3))')"
  USERNAME="synthetic_chaos_${tag}_${hex}"
  TOKEN_FILE="$CHAOS_WORK/$USERNAME.token"
  local setup
  setup="$(api setup --username "$USERNAME" --token-file "$TOKEN_FILE")" || die "api setup failed for $USERNAME"
  USER_ID="$(psql_q "SELECT id FROM users WHERE username = '$USERNAME'")"
  [ -n "$USER_ID" ] || die "user $USERNAME not found in Postgres after registration"
  ROUTES="$(printf '%s' "$setup" | python3 -I -c 'import json,sys; print(" ".join(json.load(sys.stdin)["routes"]))')"
  chaos_guard_synthetic_user "$USER_ID"
  log "user $USERNAME id=$USER_ID consent recorded, preferences set, $(echo "$ROUTES" | wc -w | tr -d ' ') routes"
  ATTEMPT_IDS=()
}

route_n() { echo "$ROUTES" | tr ' ' '\n' | sed -n "$(( ($1 % $(echo "$ROUTES" | wc -w)) + 1 ))p"; }
now_ms() { python3 -I -c 'from datetime import datetime,timezone; print(datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00","Z"))'; }
shifted_ms() { python3 -I -c 'from datetime import datetime,timezone,timedelta; import sys; print((datetime.now(timezone.utc)+timedelta(seconds=float(sys.argv[1]))).isoformat(timespec="milliseconds").replace("+00:00","Z"))' "$1"; }

# POSTs one attempt; sets LAST_ATTEMPT_ID, LAST_OCCURRED_AT, LAST_STATUS and appends to ATTEMPT_IDS.
chaos_post_attempt() { # $1 occurred_at|now, $2 sent true|false, $3 route index (default count)
  local idx="${3:-${#ATTEMPT_IDS[@]}}" out
  out="$(api attempt --token-file "$TOKEN_FILE" --route-variant-id "$(route_n "$idx")" --occurred-at "$1" --sent "$2")" || die "POST attempt failed: $out"
  LAST_STATUS="$(printf '%s' "$out" | json_get status)"
  LAST_ATTEMPT_ID="$(printf '%s' "$out" | json_get attempt_id)"
  LAST_OCCURRED_AT="$(printf '%s' "$out" | json_get occurred_at)"
  ATTEMPT_IDS+=("$LAST_ATTEMPT_ID")
  log "POST /v1/me/attempts -> $LAST_STATUS attempt_id=$LAST_ATTEMPT_ID occurred_at=$LAST_OCCURRED_AT sent=$2 requests=$(printf '%s' "$out" | json_get requests)"
}

chaos_patch_attempt() { # $1 attempt id, $2 if-match revision, $3 JSON body -> sets LAST_REVISION
  local out
  out="$(api patch --token-file "$TOKEN_FILE" --attempt-id "$1" --if-match "$2" --body "$3")" || die "PATCH attempt $1 failed: $out"
  LAST_REVISION="$(printf '%s' "$out" | json_get revision)"
  log "PATCH /v1/me/attempts/$1 If-Match \"$2\" $3 -> $(printf '%s' "$out" | json_get status) revision=$LAST_REVISION status=$(printf '%s' "$out" | json_get attempt_status)"
}

chaos_recommend() { api recommend --token-file "$TOKEN_FILE"; }

chaos_log_stream_row() { # $1 user
  local row; row="$(stream_row "$1")"
  if [ -z "$row" ]; then log "STREAM row: none"; else log "STREAM row: cutoff_w=$(echo "$row" | cut -d'|' -f1) checksum=$(echo "$row" | cut -d'|' -f2) partition=$(echo "$row" | cut -d'|' -f3) offset=$(echo "$row" | cut -d'|' -f4) feature_as_of=$(echo "$row" | cut -d'|' -f5) aggregates=$(echo "$row" | cut -d'|' -f6-)"; fi
}
chaos_log_sync_row() {
  local row; row="$(sync_row "$1")"
  if [ -z "$row" ]; then log "SYNC row: none"; else log "SYNC row: cutoff_w=$(echo "$row" | cut -d'|' -f1) checksum=$(echo "$row" | cut -d'|' -f2) feature_as_of=$(echo "$row" | cut -d'|' -f3) aggregates=$(echo "$row" | cut -d'|' -f4-)"; fi
}

# Waits until the STREAM row carries exactly this checksum (and, when given, this cutoff_w).
chaos_wait_stream_checksum() { # $1 user, $2 checksum, $3 timeout s, [$4 cutoff_w]
  local start now row
  start=$(date +%s)
  while true; do
    row="$(stream_row "$1")"
    if [ -n "$row" ] && [ "$(echo "$row" | cut -d'|' -f2)" = "$2" ] && { [ -z "${4:-}" ] || [ "$(echo "$row" | cut -d'|' -f1)" = "$4" ]; }; then
      log "STREAM row reached checksum ${2:0:12}.. after $(( $(date +%s) - start )) s"; return 0
    fi
    now=$(date +%s)
    [ $((now - start)) -ge "$3" ] && { log "STREAM row did not reach checksum $2 within $3 s (row: ${row:-none})"; return 1; }
    sleep 2
  done
}

# One convergence probe (used by chaos_converge): prints the failing condition or "ok".
converge_probe() { # $1 user, $2 last occurred_at, $3.. excluded attempt ids
  local user="$1" last="$2" backlog lag_stream lag_mat row cutoff checksum expect
  shift 2
  backlog="$(outbox_backlog)"; [ "$backlog" = 0 ] || { echo "outbox backlog $backlog"; return 1; }
  lag_stream="$(group_lag beacon-stream)"; [ "$lag_stream" = 0 ] || { echo "beacon-stream lag $lag_stream"; return 1; }
  lag_mat="$(group_lag beacon-materializer)"; [ "$lag_mat" = 0 ] || { echo "beacon-materializer lag $lag_mat"; return 1; }
  row="$(stream_row "$user")"; [ -n "$row" ] || { echo "no STREAM row"; return 1; }
  cutoff="$(echo "$row" | cut -d'|' -f1)"; checksum="$(echo "$row" | cut -d'|' -f2)"
  [ "$(psql_q "SELECT '$cutoff'::timestamptz >= '$last'::timestamptz")" = t ] || { echo "cutoff_w $cutoff < last occurred_at $last"; return 1; }
  expect="$(expected_checksum "$user" "$cutoff" "$@")"
  [ "$checksum" = "$expect" ] || { echo "STREAM checksum $checksum != expected $expect at W=$cutoff"; return 1; }
  echo "ok"
}

# The settle step of test/integration/parity/README.md: outbox backlog 0, Flink's attempts.v1
# group and the materializer's climber-features.v1 group both at lag 0, the user's STREAM row
# present with cutoff_w >= the last admitted event's occurred_at and its checksum equal to the
# contract checksum re-derived from Postgres at that W (minus attempts the scenario expects the
# stream to lack). Bounded by CHAOS_CONVERGE_TIMEOUT_S.
chaos_converge() { # $1 user, $2 last occurred_at, $3.. excluded attempt ids
  local start now why
  start=$(date +%s)
  log "converge: waiting (<= ${CHAOS_CONVERGE_TIMEOUT_S} s) for user $1 through occurred_at >= $2${3:+ excluding $*}"
  while true; do
    why="$(converge_probe "$@")" && { log "converge: $why after $(( $(date +%s) - start )) s"; chaos_log_stream_row "$1"; CONVERGE_S=$(( $(date +%s) - start )); return 0; }
    now=$(date +%s)
    if [ $((now - start)) -ge "$CHAOS_CONVERGE_TIMEOUT_S" ]; then
      log "converge: TIMED OUT after $CHAOS_CONVERGE_TIMEOUT_S s, last condition: $why"; chaos_log_stream_row "$1"; CONVERGE_S=$((now - start)); return 1
    fi
    sleep 3
  done
}

# ---------------------------------------------------------------------------------------------
# api jar profiles (parity, reconcile, recompute-features, dlq-redrive)

jar_sha() { shasum -a 256 "$CHAOS_API_JAR" 2>/dev/null | cut -d' ' -f1; }

# Runs a Makefile target, then checks whether the api jar on disk changed (the target's Gradle
# step rewrites the jar that the running api container has bind-mounted; the runbook documents
# that a JVM whose jar changes underneath it fails lazy class loading). When it changed the api
# is restarted so the stack keeps running the jar on disk; the transcript says so loudly.
run_make() {
  local before after status=0
  before="$(jar_sha)"
  (cd "$CHAOS_ROOT" && make "$@") || status=$?
  after="$(jar_sha)"
  if [ "$before" != "$after" ]; then
    log "WARNING: services/api/build/libs/api-0.1.0.jar was rebuilt by 'make $*' under the running api container (sha ${before:0:12}.. -> ${after:0:12}..); restarting api so it runs the jar on disk"
    compose restart api >/dev/null 2>&1 || true
    wait_for "api healthy after jar rebuild" 120 3 bash -c "curl -fsS -o /dev/null --max-time 3 '$CHAOS_BASE_URL/health/ready'"
  fi
  return $status
}

# `direct` mode: the recipe lines of the Makefile targets without `./gradlew :api:bootJar`.
run_profile_direct() { # $1 profile, rest: --args
  (cd "$CHAOS_ROOT" && set -a && . ./.env && set +a && \
    BEACON_DB_URL="$CHAOS_DB_URL" "${JAVA_HOME:+$JAVA_HOME/bin/}java" -jar "$CHAOS_API_JAR" --spring.profiles.active="$1" "${@:2}")
}

# Parity for one user. $2 = out file. Prints the oracle's summary line; returns the oracle's exit
# code (0 EQUAL, 1 DIFF/SAME_W_MISMATCH, 2 harness error). PARITY_STATUS/PARITY_DIFF_KEYS/
# PARITY_CHECKSUM_DIFFERS are set from the report's detail for the user.
chaos_parity_run() { # $1 user, $2 out path
  chaos_guard_synthetic_user "$1"
  local status=0
  if [ "$CHAOS_INVOKE" = make ]; then
    run_make test-parity USER="$1" OUT="$2" || status=$?
  else
    (cd "$CHAOS_ROOT" && bash scripts/parity/parity.sh --user "$1" --out "$2") || status=$?
  fi
  PARITY_STATUS=""; PARITY_DIFF_KEYS=""; PARITY_CHECKSUM_DIFFERS=""
  # GNU make reports any failed recipe as exit 2, hiding the oracle's own 1 (diffs) vs 2 (harness
  # error). The report is only written on a completed run, so: report present and diffs or
  # same_w_mismatch > 0 means the oracle exited 1 (scripts/parity/README.md); no report means 2.
  if [ "$CHAOS_INVOKE" = make ] && [ "$status" = 2 ] && [ -f "$2" ]; then
    if [ "$(python3 -I -c 'import json,sys; r=json.load(open(sys.argv[1])); print(r["diffs"] + r["same_w_mismatch"])' "$2")" -gt 0 ]; then
      log "make exited 2 for the failed test-parity recipe; the written report has diffs, i.e. the oracle's own exit was 1"
      status=1
    fi
  fi
  if [ -f "$2" ]; then
    read -r PARITY_STATUS PARITY_DIFF_KEYS PARITY_CHECKSUM_DIFFERS < <(python3 -I -c '
import json,sys
r=json.load(open(sys.argv[1])); u=sys.argv[2]
d=next((d for d in r["details"] if d["user_id"]==u), None)
print(d["status"] if d else "NO_DETAIL", ",".join(d["diff_keys"]) if d and d["diff_keys"] else "-", str(d["checksum_differs"]).lower() if d else "-")' "$2" "$1")
    log "parity report $2: status=$PARITY_STATUS diff_keys=$PARITY_DIFF_KEYS checksum_differs=$PARITY_CHECKSUM_DIFFERS (exit $status)"
  else
    log "parity: no report written (exit $status)"
  fi
  return $status
}

chaos_reconcile() {
  if [ "$CHAOS_INVOKE" = make ]; then run_make reconcile; else run_profile_direct reconcile; fi
}
chaos_recompute() { # $1 user, $2 W
  if [ "$CHAOS_INVOKE" = make ]; then run_make recompute-features USER="$1" W="$2"; else run_profile_direct recompute "--recompute.user=$1" "--recompute.w=$2"; fi
}
chaos_dlq_redrive_dry() { # $1 comma-separated event ids
  if [ "$CHAOS_INVOKE" = make ]; then run_make dlq-redrive EVENTS="$1" DRY_RUN=1; else (cd "$CHAOS_ROOT" && bash scripts/dlq-redrive.sh --events "$1" --dry-run); fi
}

# ---------------------------------------------------------------------------------------------
# result hand-off to the harness

# Writes the KEY=VALUE result file the harness sources after the scenario script exits 0.
#   EXPECT_ACTIVE          number of ACTIVE attempts the user must have
#   EXPECT_ATTEMPTS        "<attempt_id>:<revision>:<status> ..." every row that must exist
#   LAST_OCCURRED_AT       the STREAM row's cutoff_w must reach this
#   STREAM_EXCLUDE         attempt ids the STREAM row is expected NOT to include (late events)
#   PARITY_MODE            zero (exit 0 required) | expect-diff (exit 1 with DIFF required)
#   EXPECT_DLQ_GROWTH      records the scenario is expected to have added to stream-dlq.v1
chaos_result() { # $1 expect_active, $2 last occurred_at, $3 parity mode, $4 dlq growth, $5 stream-exclude ids (space list), rest: id:rev:status
  local f="${CHAOS_RESULT:-$CHAOS_WORK/result.env}"
  {
    echo "SCENARIO_USER_ID=$USER_ID"
    echo "SCENARIO_USERNAME=$USERNAME"
    echo "EXPECT_ACTIVE=$1"
    echo "LAST_OCCURRED_AT=$2"
    echo "PARITY_MODE=$3"
    echo "EXPECT_DLQ_GROWTH=$4"
    echo "STREAM_EXCLUDE=\"$5\""
    echo "EXPECT_ATTEMPTS=\"${*:6}\""
  } >"$f"
  log "result written to $f: user=$USER_ID active=$1 last_occurred_at=$2 parity=$3 dlq_growth=$4"
}

# Expected rows for every id in ATTEMPT_IDS at revision 1 ACTIVE (override entries by hand).
chaos_expect_all_rev1() { local id; for id in "${ATTEMPT_IDS[@]}"; do printf '%s:1:ACTIVE ' "$id"; done; }
