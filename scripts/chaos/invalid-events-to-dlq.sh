#!/usr/bin/env bash
# BC-0307 scenario invalid-events-to-dlq (ADR-0004 "checksum conflict" neighbourhood, honestly):
# the equal-offset + different-checksum conflict cannot be provoked from outside Kafka (offsets are
# assigned by the broker, so a hand-made record always arrives at a HIGHER offset and is correctly
# applied); CHECKSUM_CONFLICT is proven by MaterializerListenerIT (BC-0305). This scenario provokes
# the other DLQ path end to end for both consumers: an attempts.v1 record with a `notes` member
# (NOTES_PRESENT, rejected by the stream job's ParseAttempts) and a climber-features.v1 record with
# a malformed provenance_checksum (SCHEMA_VIOLATION, rejected by the api materializer), both keyed
# by the scenario user and produced with kafka-console-producer inside the broker container.
# Asserts: exactly 2 stream-dlq.v1 records keyed by the user (original_topic attempts.v1 /
# climber-features.v1, consumer stream / api), both consumers keep working (a real attempt
# converges afterwards), `make dlq-redrive EVENTS=<ids> DRY_RUN=1` prints the two audit lines
# with result=DRY_RUN and publishes nothing. Harness: parity zero, DLQ growth exactly 2.
set -euo pipefail
export CHAOS_SCENARIO="${CHAOS_SCENARIO:-invalid-events-to-dlq}"
. "$(cd "$(dirname "${BASH_SOURCE[0]}")/../../test/chaos/stream" && pwd)/lib.sh"
chaos_guard_base_url
chaos_work_dir

chaos_new_user dlq
DLQ0="$(topic_end_offsets_sum stream-dlq.v1)"
NOW="$(now_ms)"
EV_ATTEMPT="$(python3 -I -c '
import json,sys,uuid
u,r,t=sys.argv[1:4]
print(json.dumps({"schema_version":1,"event_id":str(uuid.uuid4()),"event_type":"ATTEMPT_RECORDED","user_id":u,"attempt_id":str(uuid.uuid4()),"attempt_revision":1,"route_variant_id":r,"occurred_at":t,"ingested_at":t,"attributes":{"sent":True,"attempts_count":1,"status":"ACTIVE","notes":"synthetic free text that must never reach a consumer"},"trace_id":"bc-0307-chaos","producer":"api"},separators=(",",":")))' "$USER_ID" "$(route_n 0)" "$NOW")"
EV_FEATURE="$(python3 -I -c '
import json,sys,uuid
u,t=sys.argv[1:3]
agg={"attempts_7d":0,"attempts_30d":0,"attempts_90d":0,"sends_7d":0,"sends_30d":0,"sends_90d":0,"send_rate_by_grade_band_90d":{},"recent_variant_ids":[],"last_session_at":None,"max_sent_grade_numeric_90d":None,"distinct_sessions_30d":0}
print(json.dumps({"schema_version":1,"event_id":str(uuid.uuid4()),"user_id":u,"feature_schema_version":"climber-features.v1","feature_as_of":t,"source_watermark":t,"provenance_checksum":"not-a-sha256-checksum","aggregates":agg,"produced_at":t,"producer":"stream"},separators=(",",":")))' "$USER_ID" "$NOW")"
log "producing an attempts.v1 record with attributes.notes (NOTES_PRESENT) keyed by $USER_ID"
printf '%s|%s\n' "$USER_ID" "$EV_ATTEMPT" | produce_keyed attempts.v1
log "producing a climber-features.v1 record with provenance_checksum='not-a-sha256-checksum' (SCHEMA_VIOLATION) keyed by $USER_ID"
printf '%s|%s\n' "$USER_ID" "$EV_FEATURE" | produce_keyed climber-features.v1

pred_dlq_grew() { [ "$(topic_end_offsets_sum stream-dlq.v1)" -ge "$1" ]; }
wait_for "stream-dlq.v1 grew by 2 (from $DLQ0)" 60 3 pred_dlq_grew "$((DLQ0 + 2))" || die "DLQ did not receive both records"
DLQ_ROWS="$(consume_all stream-dlq.v1 10000 | python3 -I -c '
import json,sys
u=sys.argv[1]
for line in sys.stdin:
    parts=line.rstrip("\n").split("\t")
    try: v=json.loads(parts[-1])
    except Exception: continue
    if v.get("original_key")!=u: continue
    headers=next((p for p in parts if "dlq.consumer:" in p), "")
    consumer=next((h.split(":",1)[1] for h in headers.split(",") if h.startswith("dlq.consumer:")), "?")
    print("%s %s %s %s attempt=%s" % (v["event_id"], v["original_topic"], v["error_code"], consumer, v["attempt"]))' "$USER_ID")"
printf '%s\n' "$DLQ_ROWS" | sed 's/^/  dlq record (event_id topic error_code consumer): /'
assert_eq "stream-dlq.v1 records keyed by the user" 2 "$(printf '%s\n' "$DLQ_ROWS" | grep -c . || true)"
assert_eq "attempts.v1 rejection" 1 "$(printf '%s\n' "$DLQ_ROWS" | grep -c 'attempts.v1 NOTES_PRESENT stream' || true)"
assert_eq "climber-features.v1 rejection" 1 "$(printf '%s\n' "$DLQ_ROWS" | grep -c 'climber-features.v1 SCHEMA_VIOLATION api' || true)"
ID1="$(printf '%s\n' "$DLQ_ROWS" | awk '$2=="attempts.v1"{print $1}')"
ID2="$(printf '%s\n' "$DLQ_ROWS" | awk '$2=="climber-features.v1"{print $1}')"
assert_eq "no STREAM row was written from the invalid feature record" "" "$(stream_row "$USER_ID")"

log "both consumers keep processing: a real attempt must converge"
chaos_post_attempt now true
T="$LAST_OCCURRED_AT"; ID="$LAST_ATTEMPT_ID"
chaos_wait_stream_checksum "$USER_ID" "$(expected_checksum "$USER_ID" all)" 90 || die "the real attempt did not materialize after the DLQ records"
chaos_log_stream_row "$USER_ID"

log "make dlq-redrive EVENTS=$ID1,$ID2 DRY_RUN=1 (audit lines only; a NOTES_PRESENT record is never really redriven, docs/runbooks/dlq.md)"
REDRIVE="$(chaos_dlq_redrive_dry "$ID1,$ID2" 2>&1 | tee /dev/stderr | grep -E '^redrive ' || true)"
assert_eq "dry-run audit lines with result=DRY_RUN" 2 "$(printf '%s\n' "$REDRIVE" | grep -c 'result=DRY_RUN' || true)"
assert_eq "dry run published nothing" 1 "$(printf '%s\n' "$REDRIVE" | grep -c 'published=0' || true)"
assert_eq "attempts.v1 record count unchanged by the dry run (DLQ growth only)" "$((DLQ0 + 2))" "$(topic_end_offsets_sum stream-dlq.v1)"
chaos_result 1 "$T" zero 2 "" "$ID:1:ACTIVE"
