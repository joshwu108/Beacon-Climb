#!/usr/bin/env bash
# BC-0307 scenario api-sigkill-during-burst (ADR-0004 "materializer crash before commit", end to
# end): a burst of 50 POST /v1/me/attempts (sequential, one Idempotency-Key each) is started and
# the api container is SIGKILLed ~0.5 s into it, then brought back with `docker compose up -d
# --wait api`. The client keeps retrying the in-flight and remaining keys with the SAME key until
# it gets a definite answer (ADR-0002: a replay of a committed write returns 200 with the same
# body, a never-committed one returns 201). The kill lands at an arbitrary point of the request
# path, the outbox publisher and the materializer at once; the exact commit/ack interleaving
# of the materializer is proven by MaterializerListenerIT (BC-0305), this scenario proves the
# system converges after the crash: every key ends 201 or 200, the user has exactly 50 ACTIVE
# attempts (= distinct keys), the backlog drains, the STREAM row converges, parity zero, no DLQ
# growth. Honest name: the api is killed, not a test hook inside the materializer.
set -euo pipefail
export CHAOS_SCENARIO="${CHAOS_SCENARIO:-api-sigkill-during-burst}"
. "$(cd "$(dirname "${BASH_SOURCE[0]}")/../../test/chaos/stream" && pwd)/lib.sh"
chaos_guard_base_url
chaos_work_dir

chaos_new_user killburst
BURST_OUT="$CHAOS_WORK/burst-during.jsonl"
SUMMARY_FILE="$CHAOS_WORK/burst-during.json"
log "starting a burst of 50 attempts in the background (retry deadline 150 s per key)"
api burst --token-file "$TOKEN_FILE" --count 50 --route-variant-ids "$(echo "$ROUTES" | tr ' ' ',')" --deadline 150 --out "$BURST_OUT" >"$SUMMARY_FILE" 2>"$CHAOS_WORK/burst-during.err" &
BURST_PID=$!
sleep 0.5
T_KILL="$(ts)"
log "docker compose kill -s SIGKILL api at $T_KILL (burst rows written so far: $(wc -l <"$BURST_OUT" 2>/dev/null | tr -d ' ' || echo 0))"
compose kill -s SIGKILL api
log "api killed; docker compose up -d --wait api"
RESTART_START=$(date +%s)
compose up -d --wait api
log "api healthy again after $(( $(date +%s) - RESTART_START )) s"
wait "$BURST_PID" && BURST_RC=0 || BURST_RC=$?
cat "$CHAOS_WORK/burst-during.err" | sed 's/^/  /' || true
SUMMARY="$(cat "$SUMMARY_FILE")"
log "burst summary: $SUMMARY"
[ "$BURST_RC" = 0 ] || die "burst ended with failures (exit $BURST_RC)"
assert_eq "distinct idempotency keys" 50 "$(printf '%s' "$SUMMARY" | json_get distinct_keys)"
OK201="$(printf '%s' "$SUMMARY" | json_get ok_201)"; OK200="$(printf '%s' "$SUMMARY" | json_get ok_200_replay)"
assert_eq "keys answered 201 + 200 (replay after a lost response)" 50 "$((OK201 + OK200))"
log "keys retried after a connection error: $(printf '%s' "$SUMMARY" | json_get keys_retried), connection errors: $(printf '%s' "$SUMMARY" | json_get connection_errors), outage seen by the client: $(printf '%s' "$SUMMARY" | json_get first_error_at) .. $(printf '%s' "$SUMMARY" | json_get last_error_at), replays (200): $OK200"
assert_eq "attempt rows for the user == distinct keys" 50 "$(psql_q "SELECT count(*) FROM attempts WHERE user_id = '$USER_ID'")"
assert_eq "attempt rows with a distinct idempotency key" 50 "$(psql_q "SELECT count(DISTINCT submission_idempotency_key) FROM attempts WHERE user_id = '$USER_ID'")"
pred_backlog_zero() { [ "$(outbox_backlog)" = 0 ]; }
wait_for "outbox backlog drained after the restart" 90 2 pred_backlog_zero || die "outbox backlog did not drain"
LAST="$(psql_q "SELECT to_char(max(occurred_at) AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.MS\"Z\"') FROM attempts WHERE user_id = '$USER_ID'")"
EXPECT_ROWS="$(python3 -I -c 'import json,sys; print(" ".join(f"{i}:1:ACTIVE" for i in json.load(open(sys.argv[1]))["attempt_ids"]))' "$SUMMARY_FILE")"
chaos_result 50 "$LAST" zero 0 "" $EXPECT_ROWS
