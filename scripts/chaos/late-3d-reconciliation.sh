#!/usr/bin/env bash
# BC-0307 scenario late-3d-reconciliation (ADR-0004 "event after the lateness boundary",
# "reconciliation"): two attempts now, then one with occurred_at = now - 3 d, which is beyond the
# late threshold (watermark - 24 h ~ now - 48 h). The stream job routes its ORIGINAL bytes to
# stream-reconciliation.v1; the api's BC-0309 consumer stores a reconciliation_events row; the
# STREAM row never includes the attempt; the api's own attempts hook (SYNC row) does.
#
# Parity, handled honestly: the oracle compares the STREAM row at ITS cutoff_w (= the 2nd
# attempt's occurred_at, T2) against the batch at W = T2, and the 3-day-old attempt has occurred_at
# <= T2, so the batch INCLUDES it and the STREAM row does not -> the oracle reports DIFF with
# checksum_differs = true. That DIFF is asserted here (exit 1, status DIFF) and shown to be exactly
# the late attempt: checksum(batch at T2 minus the late id) == STREAM checksum. Then `make
# reconcile` closes the row (RECONCILED or ALREADY_CURRENT), the SYNC row covers all three, the
# read path serves features_source SYNC with features_cutoff_w >= the reconcile W, and parity is
# asserted on the repaired SYNC view: `make recompute-features USER=<id> W=<SYNC cutoff_w>` must
# print the SYNC row's checksum and leave its aggregates byte-identical (a same-W re-derivation).
#
# Clean-up that keeps the database parity-clean for the final all-users oracle: the late attempt is
# retracted (revision 2, also late -> a second reconciliation row, reconciled again). The ACTIVE set
# then equals what the STREAM row holds, so the harness's final parity for this user is zero diffs.
# A batch rebuild that republishes the STREAM row (ADR-0004) is out of scope.
set -euo pipefail
export CHAOS_SCENARIO="${CHAOS_SCENARIO:-late-3d-reconciliation}"
. "$(cd "$(dirname "${BASH_SOURCE[0]}")/../../test/chaos/stream" && pwd)/lib.sh"
chaos_guard_base_url
chaos_work_dir

pred_recon_rows() { [ "$(psql_q "SELECT count(*) FROM reconciliation_events WHERE user_id = '$1' AND attempt_id = '$2' AND attempt_revision = $3")" -ge 1 ]; }

chaos_new_user late3d
chaos_post_attempt now true
chaos_post_attempt now false
T2="$LAST_OCCURRED_AT"
chaos_wait_stream_checksum "$USER_ID" "$(expected_checksum "$USER_ID" all)" 90 || die "STREAM row did not reach the 2-attempt checksum"
STREAM_BEFORE="$(stream_row "$USER_ID")"
chaos_log_stream_row "$USER_ID"

LATE_AT="$(shifted_ms -259200)"
log "posting an attempt 3 d in the past: occurred_at=$LATE_AT (beyond watermark - 24 h)"
chaos_post_attempt "$LATE_AT" true
LATE_ID="$LAST_ATTEMPT_ID"
wait_for "reconciliation_events row for $LATE_ID revision 1" 60 2 pred_recon_rows "$USER_ID" "$LATE_ID" 1 || die "no reconciliation_events row within 60 s"
reconciliation_rows "$USER_ID" | sed 's/^/  reconciliation row (id|attempt|rev|type|reason|resolution|resolved): /'
assert_eq "reconciliation row reason" LATE_BEYOND_WINDOW "$(psql_q "SELECT reason FROM reconciliation_events WHERE attempt_id = '$LATE_ID' AND attempt_revision = 1")"
assert_eq "reconciliation row open before reconcile" f "$(psql_q "SELECT resolved_at IS NOT NULL FROM reconciliation_events WHERE attempt_id = '$LATE_ID' AND attempt_revision = 1")"

STREAM_NOW="$(stream_row "$USER_ID")"
assert_eq "STREAM row unchanged by the late event (cutoff_w|checksum|offset)" "$(echo "$STREAM_BEFORE" | cut -d'|' -f1,2,4)" "$(echo "$STREAM_NOW" | cut -d'|' -f1,2,4)"
STREAM_CK="$(echo "$STREAM_NOW" | cut -d'|' -f2)"
assert_eq "batch at W=T2 WITHOUT the late attempt == STREAM checksum" "$(expected_checksum "$USER_ID" "$T2" "$LATE_ID")" "$STREAM_CK"
assert_ne "batch at W=T2 WITH the late attempt differs from the STREAM checksum (the expected DIFF is exactly $LATE_ID)" "$STREAM_CK" "$(expected_checksum "$USER_ID" "$T2")"
assert_eq "SYNC row (attempts hook) already includes all 3" "$(expected_checksum "$USER_ID" all)" "$(sync_field "$USER_ID" 2)"
chaos_log_sync_row "$USER_ID"

if [ "${PARITY:-run}" != skip ]; then
  st=0; chaos_parity_run "$USER_ID" "$CHAOS_EVIDENCE/parity-late-3d-reconciliation-before-reconcile.json" || st=$?
  [ "$st" = 1 ] && [ "$PARITY_STATUS" = DIFF ] && [ "$PARITY_CHECKSUM_DIFFERS" = true ] \
    && log "ASSERT PASS parity oracle before reconcile: exit 1, DIFF, checksum_differs=true, diff_keys=$PARITY_DIFF_KEYS (the measured, expected signal)" \
    || die "parity before reconcile: expected exit 1/DIFF/checksum_differs, got exit $st status $PARITY_STATUS checksum_differs=$PARITY_CHECKSUM_DIFFERS"
else
  log "parity oracle before reconcile skipped (PARITY=skip); the SQL re-derivation above stands in"
fi

log "running the repair job (make reconcile)"
RECON_OUT="$(chaos_reconcile 2>&1 | tee /dev/stderr | grep -E "^reconcile" || true)"
LINE="$(printf '%s\n' "$RECON_OUT" | grep "user_id=$USER_ID" || true)"
[ -n "$LINE" ] || die "reconcile printed no line for user $USER_ID"
log "reconcile line: $LINE"
RES="$(printf '%s' "$LINE" | sed -n 's/.*resolution=\([A-Z_]*\).*/\1/p')"
case "$RES" in RECONCILED|ALREADY_CURRENT) log "ASSERT PASS reconcile resolution $RES" ;; *) die "reconcile resolution '$RES'" ;; esac
assert_eq "every reconciliation row of the user resolved" 0 "$(psql_q "SELECT count(*) FROM reconciliation_events WHERE user_id = '$USER_ID' AND resolved_at IS NULL")"
RESOLVED_W="$(psql_q "SELECT to_char(max(resolved_cutoff_w) AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.MS\"Z\"') FROM reconciliation_events WHERE user_id = '$USER_ID'")"
SYNC_W="$(sync_field "$USER_ID" 1)"; SYNC_CK="$(sync_field "$USER_ID" 2)"
assert_eq "SYNC cutoff_w >= resolved_cutoff_w ($RESOLVED_W)" t "$(psql_q "SELECT '$SYNC_W'::timestamptz >= '$RESOLVED_W'::timestamptz")"
assert_eq "SYNC checksum after reconcile covers all 3 attempts" "$(expected_checksum "$USER_ID" all)" "$SYNC_CK"
assert_eq "STREAM cutoff_w < SYNC cutoff_w (expected: the STREAM row lacks the late event)" t "$(psql_q "SELECT '$(stream_field "$USER_ID" 1)'::timestamptz < '$SYNC_W'::timestamptz")"

REC="$(chaos_recommend)" || die "recommendations call failed: $REC"
log "POST /v1/me/sessions/recommendations -> features_source=$(printf '%s' "$REC" | json_get features_source) features_cutoff_w=$(printf '%s' "$REC" | json_get features_cutoff_w) feature_as_of=$(printf '%s' "$REC" | json_get feature_as_of)"
assert_eq "read path serves the repaired SYNC row" SYNC "$(printf '%s' "$REC" | json_get features_source)"
assert_eq "features_cutoff_w >= the reconcile W" t "$(psql_q "SELECT '$(printf '%s' "$REC" | json_get features_cutoff_w)'::timestamptz >= '$RESOLVED_W'::timestamptz")"

log "SYNC-view parity: make recompute-features USER=$USER_ID W=$SYNC_W (same-W re-derivation, write allowed by the monotonic rule at equal W)"
AGG_BEFORE="$(sync_field "$USER_ID" 4)"
RECOMP="$(chaos_recompute "$USER_ID" "$SYNC_W" 2>&1 | tee /dev/stderr | grep "^recomputed user_id=$USER_ID" || true)"
[ -n "$RECOMP" ] || die "recompute-features printed no line for the user"
assert_eq "recompute at W=SYNC cutoff_w prints the SYNC checksum" "$SYNC_CK" "$(printf '%s' "$RECOMP" | sed -n 's/.*checksum=\([0-9a-f]*\).*/\1/p')"
assert_eq "SYNC aggregates byte-identical after the re-derivation" "$AGG_BEFORE" "$(sync_field "$USER_ID" 4)"
assert_eq "SYNC checksum unchanged after the re-derivation" "$SYNC_CK" "$(sync_field "$USER_ID" 2)"

log "clean-up: retracting the late attempt (revision 2, also late -> reconciliation) and reconciling again"
chaos_patch_attempt "$LATE_ID" 1 '{"retract": true}'
wait_for "reconciliation_events row for $LATE_ID revision 2" 60 2 pred_recon_rows "$USER_ID" "$LATE_ID" 2 || die "no reconciliation row for the retraction within 60 s"
chaos_reconcile 2>&1 | grep -E "^reconcile" || true
assert_eq "every reconciliation row resolved after the second run" 0 "$(psql_q "SELECT count(*) FROM reconciliation_events WHERE user_id = '$USER_ID' AND resolved_at IS NULL")"
assert_eq "after the retraction, batch at W=T2 (ACTIVE only) == STREAM checksum" "$(expected_checksum "$USER_ID" "$T2")" "$(stream_field "$USER_ID" 2)"
reconciliation_rows "$USER_ID" | sed 's/^/  reconciliation row (id|attempt|rev|type|reason|resolution|resolved): /'
chaos_log_stream_row "$USER_ID"; chaos_log_sync_row "$USER_ID"

chaos_result 2 "$T2" zero 0 "" "${ATTEMPT_IDS[0]}:1:ACTIVE" "${ATTEMPT_IDS[1]}:1:ACTIVE" "$LATE_ID:2:RETRACTED"
