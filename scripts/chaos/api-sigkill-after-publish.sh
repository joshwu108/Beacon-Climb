#!/usr/bin/env bash
# BC-0307 scenario api-sigkill-after-publish (ADR-0004 "materializer crash after commit", end to
# end): a burst of 50 attempts is written and acknowledged (every key 201), then the api is
# SIGKILLed the moment beacon_outbox_backlog_rows first reads 0 on /prometheus, i.e. right after
# the publisher committed the last published_at and while the materializer may still be consuming
# the feature records the stream job emits for those 50 events. On restart the materializer
# resumes from its last committed offset (manual ack after commit, BC-0305): re-delivered records
# land as DUPLICATE (equal offset, same checksum) or APPLIED (higher offset), never as a conflict.
# Asserts: 50 ACTIVE attempts, the STREAM row converges, parity zero, no DLQ growth; records the
# materializer position at the kill and after convergence. The precise commit/ack interleaving is
# proven by MaterializerListenerIT; this scenario proves end-to-end convergence after a kill at
# that point of the pipeline.
set -euo pipefail
export CHAOS_SCENARIO="${CHAOS_SCENARIO:-api-sigkill-after-publish}"
. "$(cd "$(dirname "${BASH_SOURCE[0]}")/../../test/chaos/stream" && pwd)/lib.sh"
chaos_guard_base_url
chaos_work_dir

chaos_new_user killpub
BURST_OUT="$CHAOS_WORK/burst-after.jsonl"
SUMMARY="$(api burst --token-file "$TOKEN_FILE" --count 50 --route-variant-ids "$(echo "$ROUTES" | tr ' ' ',')" --deadline 60 --out "$BURST_OUT")" || die "burst failed: $SUMMARY"
log "burst summary: $SUMMARY"
assert_eq "every key answered 201 before the kill" 50 "$(printf '%s' "$SUMMARY" | json_get ok_201)"
log "polling /prometheus beacon_outbox_backlog_rows until it reads 0, then SIGKILL immediately"
START=$(date +%s)
while true; do
  v="$(prometheus_gauge beacon_outbox_backlog_rows || echo '')"
  [ "${v%.*}" = 0 ] && break
  [ $(( $(date +%s) - START )) -ge 60 ] && die "beacon_outbox_backlog_rows did not return to 0 within 60 s (last $v)"
  sleep 0.2
done
ROW_AT_KILL="$(stream_row "$USER_ID" || true)"
log "beacon_outbox_backlog_rows=0 at $(ts) after $(( $(date +%s) - START )) s; STREAM row at kill: ${ROW_AT_KILL:-none}"
compose kill -s SIGKILL api
log "api SIGKILLed at $(ts); docker compose up -d --wait api"
RESTART_START=$(date +%s)
compose up -d --wait api
log "api healthy again after $(( $(date +%s) - RESTART_START )) s"
assert_eq "attempt rows for the user" 50 "$(psql_q "SELECT count(*) FROM attempts WHERE user_id = '$USER_ID'")"
pred_backlog_zero() { [ "$(outbox_backlog)" = 0 ]; }
wait_for "outbox backlog 0 after the restart" 90 2 pred_backlog_zero || die "outbox backlog did not drain"
chaos_wait_stream_checksum "$USER_ID" "$(expected_checksum "$USER_ID" all)" 120 || die "STREAM row did not converge over the 50 attempts"
log "STREAM row after convergence: offset $(stream_field "$USER_ID" 4) (at kill: $(echo "${ROW_AT_KILL:-|||none}" | cut -d'|' -f4))"
LAST="$(psql_q "SELECT to_char(max(occurred_at) AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.MS\"Z\"') FROM attempts WHERE user_id = '$USER_ID'")"
EXPECT_ROWS="$(printf '%s' "$SUMMARY" | python3 -I -c 'import json,sys; print(" ".join(f"{i}:1:ACTIVE" for i in json.load(sys.stdin)["attempt_ids"]))')"
chaos_result 50 "$LAST" zero 0 "" $EXPECT_ROWS
