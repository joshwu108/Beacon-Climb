#!/usr/bin/env bash
# BC-0307 scenario correction-then-retraction (ADR-0004 "out-of-order revision", "retraction"):
# one attempt (revision 1), PATCH sent=false (revision 2), PATCH retract (revision 3). The STREAM
# row must follow every revision and end at the empty set: checksum
# e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855, attempts_90d 0,
# last_session_at null, feature_as_of = source_watermark (climber-features.v1.md section 10),
# while the SYNC row stores feature_as_of null for the same set. ACTIVE attempts 0, the row exists
# as revision 3 RETRACTED.
set -euo pipefail
export CHAOS_SCENARIO="${CHAOS_SCENARIO:-correction-then-retraction}"
. "$(cd "$(dirname "${BASH_SOURCE[0]}")/../../test/chaos/stream" && pwd)/lib.sh"
chaos_guard_base_url
chaos_work_dir

agg_key() { printf '%s' "$1" | python3 -I -c 'import json,sys; v=json.load(sys.stdin).get(sys.argv[1]); print("null" if v is None else v)' "$2"; }

chaos_new_user corr
chaos_post_attempt now true
ID="$LAST_ATTEMPT_ID"; T="$LAST_OCCURRED_AT"
chaos_wait_stream_checksum "$USER_ID" "$(expected_checksum "$USER_ID" all)" 90 || die "revision 1 not materialized"
chaos_log_stream_row "$USER_ID"

chaos_patch_attempt "$ID" 1 '{"sent": false}'
assert_eq "revision after correction" 2 "$LAST_REVISION"
chaos_wait_stream_checksum "$USER_ID" "$(expected_checksum "$USER_ID" all)" 90 || die "revision 2 not materialized"
ROW="$(stream_row "$USER_ID")"
assert_eq "sends_90d after sent=false" 0 "$(agg_key "$(echo "$ROW" | cut -d'|' -f6-)" sends_90d)"
chaos_log_stream_row "$USER_ID"

chaos_patch_attempt "$ID" 2 '{"retract": true}'
assert_eq "revision after retraction" 3 "$LAST_REVISION"
chaos_wait_stream_checksum "$USER_ID" "$EMPTY_SET_CHECKSUM" 90 || die "retraction not materialized"
ROW="$(stream_row "$USER_ID")"
AGG="$(echo "$ROW" | cut -d'|' -f6-)"
assert_eq "STREAM cutoff_w == the attempt's occurred_at" t "$(psql_q "SELECT '$(echo "$ROW" | cut -d'|' -f1)'::timestamptz = '$T'::timestamptz")"
assert_eq "STREAM attempts_90d after retraction" 0 "$(agg_key "$AGG" attempts_90d)"
assert_eq "STREAM last_session_at after retraction" null "$(agg_key "$AGG" last_session_at)"
assert_eq "STREAM recent_variant_ids after retraction" "[]" "$(agg_key "$AGG" recent_variant_ids)"
assert_eq "STREAM feature_as_of == source_watermark for the empty set" "$(echo "$ROW" | cut -d'|' -f1)" "$(echo "$ROW" | cut -d'|' -f5)"
assert_eq "SYNC feature_as_of null for the empty set" null "$(sync_field "$USER_ID" 3)"
assert_eq "SYNC checksum == empty-set checksum" "$EMPTY_SET_CHECKSUM" "$(sync_field "$USER_ID" 2)"
assert_eq "attempt row revision:status" "3:RETRACTED" "$(attempt_rev_status "$ID")"
chaos_log_stream_row "$USER_ID"; chaos_log_sync_row "$USER_ID"

chaos_result 0 "$T" zero 0 "" "$ID:3:RETRACTED"
