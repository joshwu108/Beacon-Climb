#!/usr/bin/env bash
# BC-0307 scenario dup-publish (ADR-0004 "duplicate"): at-least-once re-delivery of attempts.v1
# records through the real path. Five attempts are written through the api; then three of their
# outbox rows get published_at = NULL again, so the BC-0301 publisher sends the identical bytes a
# second time (same key, same event_id). The stream operator must treat the second copies as a
# stale revision (no state change, nothing emitted); the STREAM row, the parity oracle and the
# attempt count must be unaffected.
#
# Asserts here: 3 rows re-published (published_at set again), attempts.v1 holds 5 + 3 records for
# the user, Flink's attempts_stale_revision counter advanced by >= 3 when the REST metric is
# exposed, both consumer groups drained. The harness then asserts convergence, parity zero diffs,
# ACTIVE attempts == 5 at revision 1, no DLQ growth.
set -euo pipefail
export CHAOS_SCENARIO="${CHAOS_SCENARIO:-dup-publish}"
. "$(cd "$(dirname "${BASH_SOURCE[0]}")/../../test/chaos/stream" && pwd)/lib.sh"
chaos_guard_base_url
chaos_work_dir

chaos_new_user dup
for i in 1 2 3 4 5; do chaos_post_attempt now "$([ $((i % 2)) = 1 ] && echo true || echo false)"; done
LAST5="$LAST_OCCURRED_AT"
EXPECT="$(expected_checksum "$USER_ID" all)"
chaos_wait_stream_checksum "$USER_ID" "$EXPECT" 90 || die "STREAM row did not reach the 5-attempt checksum"
chaos_log_stream_row "$USER_ID"
ROW_BEFORE="$(stream_row "$USER_ID")"

pred_user_backlog_zero() { [ "$(outbox_backlog_user "$1")" = 0 ]; }
wait_for "outbox published for $USER_ID" 30 1 pred_user_backlog_zero "$USER_ID" || die "outbox rows of the user were not published"

JOB="$(flink_running_job)"
STALE0="$(flink_counter "$JOB" attempts_stale_revision)"
log "flink attempts_stale_revision before re-publish: ${STALE0:-<metric not exposed>}"
RECORDS0="$(consume_all attempts.v1 8000 | awk -F'\t' -v u="$USER_ID" '$(NF-1) == u' | wc -l | tr -d ' ')"
log "attempts.v1 records keyed by the user before re-publish: $RECORDS0"

IDS="'${ATTEMPT_IDS[0]}','${ATTEMPT_IDS[1]}','${ATTEMPT_IDS[2]}'"
log "re-publishing 3 outbox rows: UPDATE attempt_event_outbox SET published_at = NULL WHERE aggregate_id IN ($IDS)"
UPDATED="$(psql_q "WITH u AS (UPDATE attempt_event_outbox SET published_at = NULL WHERE aggregate_id IN ($IDS) AND payload_json->>'user_id' = '$USER_ID' RETURNING 1) SELECT count(*) FROM u")"
assert_eq "outbox rows reset" 3 "$UPDATED"
wait_for "the 3 rows published again" 30 1 pred_user_backlog_zero "$USER_ID" || die "re-publish did not happen"
psql_q "SELECT event_id, aggregate_id, aggregate_revision, to_char(published_at AT TIME ZONE 'UTC', 'HH24:MI:SS.MS') FROM attempt_event_outbox WHERE aggregate_id IN ($IDS) ORDER BY created_at" | sed 's/^/  outbox row: /'

pred_lag_zero() { [ "$(group_lag "$1")" = 0 ]; }
wait_for "beacon-stream (Flink) lag 0" 60 3 pred_lag_zero beacon-stream || die "Flink group did not drain"
wait_for "beacon-materializer lag 0" 60 3 pred_lag_zero beacon-materializer || die "materializer group did not drain"

RECORDS1="$(consume_all attempts.v1 8000 | awk -F'\t' -v u="$USER_ID" '$(NF-1) == u' | wc -l | tr -d ' ')"
assert_eq "attempts.v1 records for the user after re-publish (5 + 3 duplicates)" "$((RECORDS0 + 3))" "$RECORDS1"
STALE1="$(flink_counter "$JOB" attempts_stale_revision)"
if [ -n "$STALE0" ] && [ -n "$STALE1" ]; then
  assert_ge "flink attempts_stale_revision delta (duplicates ignored by the operator)" 3 "$((STALE1 - STALE0))"
else
  log "flink attempts_stale_revision not exposed through REST on this job; duplicate handling is asserted through the unchanged STREAM row instead"
fi
ROW_AFTER="$(stream_row "$USER_ID")"
assert_eq "STREAM row unchanged by the duplicates (cutoff_w|checksum|partition|offset)" "$(echo "$ROW_BEFORE" | cut -d'|' -f1-4)" "$(echo "$ROW_AFTER" | cut -d'|' -f1-4)"
chaos_log_stream_row "$USER_ID"

chaos_result 5 "$LAST5" zero 0 "" $(chaos_expect_all_rev1)
