#!/usr/bin/env bash
# BC-0307 scenario late-2h-inside-window (ADR-0004 "event at the lateness boundary", inside):
# three attempts now, then one with occurred_at = now - 2 h. The job's watermark trails the newest
# occurred_at by 24 h (StreamTopology.MAX_OUT_OF_ORDERNESS) and the late threshold is watermark -
# 24 h, so a 2 h old event is APPLIED, with W = max(watermark, occurred_at) = its own occurred_at
# (climber-features.v1.md section 10). Consequence, asserted honestly: the STREAM row moves to
# cutoff_w = now - 2 h and includes exactly the attempts with occurred_at <= that W (the late one
# only, attempts_90d = 1); the parity oracle is EQUAL at that W. A fifth attempt "now" brings W
# forward and the STREAM row then covers all five (attempts_90d = 5). The 2 h event never enters
# reconciliation (0 reconciliation_events rows).
set -euo pipefail
export CHAOS_SCENARIO="${CHAOS_SCENARIO:-late-2h-inside-window}"
. "$(cd "$(dirname "${BASH_SOURCE[0]}")/../../test/chaos/stream" && pwd)/lib.sh"
chaos_guard_base_url
chaos_work_dir

agg_key() { printf '%s' "$1" | python3 -I -c 'import json,sys; v=json.load(sys.stdin).get(sys.argv[1]); print("null" if v is None else v)' "$2"; }

chaos_new_user late2h
for i in 1 2 3; do chaos_post_attempt now true; done
T3="$LAST_OCCURRED_AT"
chaos_wait_stream_checksum "$USER_ID" "$(expected_checksum "$USER_ID" all)" 90 || die "STREAM row did not reach the 3-attempt checksum"
chaos_log_stream_row "$USER_ID"

LATE_AT="$(shifted_ms -7200)"
log "posting an attempt 2 h in the past: occurred_at=$LATE_AT (watermark ~ $T3 - 24 h, late threshold = watermark - 24 h, so this is inside the window)"
chaos_post_attempt "$LATE_AT" true
LATE_ID="$LAST_ATTEMPT_ID"
EXPECT_AT_LATE_W="$(expected_checksum "$USER_ID" "$LATE_AT")"
chaos_wait_stream_checksum "$USER_ID" "$EXPECT_AT_LATE_W" 90 || die "STREAM row did not move to the late event's W"
ROW="$(stream_row "$USER_ID")"
CUTOFF="$(echo "$ROW" | cut -d'|' -f1)"
assert_eq "STREAM cutoff_w == the late event's occurred_at (W = max(watermark, occurred_at))" t "$(psql_q "SELECT '$CUTOFF'::timestamptz = '$LATE_AT'::timestamptz")"
assert_eq "STREAM row at W = now-2h includes only the late attempt (checksum == sha256('$LATE_ID:1'))" "$(expected_checksum "$USER_ID" all "${ATTEMPT_IDS[0]}" "${ATTEMPT_IDS[1]}" "${ATTEMPT_IDS[2]}")" "$(echo "$ROW" | cut -d'|' -f2)"
assert_eq "aggregates.attempts_90d at W = now-2h" 1 "$(agg_key "$(echo "$ROW" | cut -d'|' -f6-)" attempts_90d)"
chaos_log_stream_row "$USER_ID"
if [ "${PARITY:-run}" != skip ]; then
  st=0; chaos_parity_run "$USER_ID" "$CHAOS_EVIDENCE/parity-late-2h-inside-window-at-late-W.json" || st=$?
  [ "$st" = 0 ] && [ "$PARITY_STATUS" = EQUAL ] && log "ASSERT PASS parity EQUAL at the regressed W = now-2h" || die "parity at W = now-2h: exit $st status $PARITY_STATUS"
else
  log "intermediate parity at W = now-2h skipped (PARITY=skip)"
fi

chaos_post_attempt now false
T5="$LAST_OCCURRED_AT"
chaos_wait_stream_checksum "$USER_ID" "$(expected_checksum "$USER_ID" all)" 90 || die "STREAM row did not reach the 5-attempt checksum"
ROW="$(stream_row "$USER_ID")"
assert_eq "aggregates.attempts_90d once W moved past every attempt" 5 "$(agg_key "$(echo "$ROW" | cut -d'|' -f6-)" attempts_90d)"
assert_eq "reconciliation_events rows for the user (a 2 h event is inside the window)" 0 "$(psql_q "SELECT count(*) FROM reconciliation_events WHERE user_id = '$USER_ID'")"
chaos_log_stream_row "$USER_ID"

chaos_result 5 "$T5" zero 0 "" $(chaos_expect_all_rev1)
