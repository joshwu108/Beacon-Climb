#!/usr/bin/env bash
# BC-0307 scenario same-watermark-correction (ADR-0004 "same-watermark correction"): one attempt
# (sent=true, revision 1), then PATCH sent=false right away. Both events carry the same occurred_at,
# so the stream emits the correction with an unchanged source_watermark; the materializer must
# apply it because the feature-topic OFFSET advanced (never a watermark-only guard). Asserts on the
# STREAM row: cutoff_w equal before/after, source_offset strictly greater, provenance_checksum
# changed ("<id>:1" -> "<id>:2"), sends_90d 1 -> 0, same partition. Harness: parity zero, 1 ACTIVE
# attempt at revision 2.
set -euo pipefail
export CHAOS_SCENARIO="${CHAOS_SCENARIO:-same-watermark-correction}"
. "$(cd "$(dirname "${BASH_SOURCE[0]}")/../../test/chaos/stream" && pwd)/lib.sh"
chaos_guard_base_url
chaos_work_dir

agg_key() { printf '%s' "$1" | python3 -I -c 'import json,sys; v=json.load(sys.stdin).get(sys.argv[1]); print("null" if v is None else v)' "$2"; }

chaos_new_user samew
chaos_post_attempt now true
ID="$LAST_ATTEMPT_ID"; T="$LAST_OCCURRED_AT"
chaos_wait_stream_checksum "$USER_ID" "$(expected_checksum "$USER_ID" all)" 90 || die "revision 1 not materialized"
ROW1="$(stream_row "$USER_ID")"
chaos_log_stream_row "$USER_ID"
chaos_patch_attempt "$ID" 1 '{"sent": false}'
assert_eq "revision after correction" 2 "$LAST_REVISION"
chaos_wait_stream_checksum "$USER_ID" "$(expected_checksum "$USER_ID" all)" 90 || die "revision 2 not materialized"
ROW2="$(stream_row "$USER_ID")"
chaos_log_stream_row "$USER_ID"
assert_eq "cutoff_w unchanged (same source_watermark)" "$(echo "$ROW1" | cut -d'|' -f1)" "$(echo "$ROW2" | cut -d'|' -f1)"
assert_eq "partition unchanged" "$(echo "$ROW1" | cut -d'|' -f3)" "$(echo "$ROW2" | cut -d'|' -f3)"
O1="$(echo "$ROW1" | cut -d'|' -f4)"; O2="$(echo "$ROW2" | cut -d'|' -f4)"
[ "$O2" -gt "$O1" ] && log "ASSERT PASS source_offset advanced: $O1 -> $O2" || die "source_offset did not advance: $O1 -> $O2"
assert_ne "provenance_checksum changed" "$(echo "$ROW1" | cut -d'|' -f2)" "$(echo "$ROW2" | cut -d'|' -f2)"
assert_eq "checksum == sha256('$ID:2')" "$(printf '%s' "$ID:2" | shasum -a 256 | cut -d' ' -f1)" "$(echo "$ROW2" | cut -d'|' -f2)"
assert_eq "sends_90d before" 1 "$(agg_key "$(echo "$ROW1" | cut -d'|' -f6-)" sends_90d)"
assert_eq "sends_90d after" 0 "$(agg_key "$(echo "$ROW2" | cut -d'|' -f6-)" sends_90d)"
chaos_result 1 "$T" zero 0 "" "$ID:2:ACTIVE"
