#!/usr/bin/env bash
# BC-0307 scenario kafka-restart (ADR-0004 "Kafka restart"): five attempts materialized, then the
# broker is stopped (`docker compose stop kafka`), five more attempts are written during the
# outage (the api must answer 201: the outbox decouples the write from the broker; the backlog
# grows), the broker is started again and must pass its healthcheck. The publisher drains the
# backlog, the Flink job recovers (it may restart under the fixed-delay strategy) and the STREAM row
# covers all ten. stop/start rather than `restart` so the outage window is explicit and the writes
# provably happen inside it. Asserts: 5 x 201 during the outage, backlog > 0 at that moment,
# broker healthy, backlog 0, job RUNNING, STREAM row over 10, parity zero.
set -euo pipefail
export CHAOS_SCENARIO="${CHAOS_SCENARIO:-kafka-restart}"
. "$(cd "$(dirname "${BASH_SOURCE[0]}")/../../test/chaos/stream" && pwd)/lib.sh"
chaos_guard_base_url
chaos_work_dir

chaos_new_user kafkarst
for i in 1 2 3 4 5; do chaos_post_attempt now true; done
chaos_wait_stream_checksum "$USER_ID" "$(expected_checksum "$USER_ID" all)" 90 || die "STREAM row did not reach the 5-attempt checksum"
chaos_log_stream_row "$USER_ID"
JOB="$(flink_running_job)"
log "docker compose stop kafka"
compose stop kafka
T_DOWN="$(ts)"
for i in 1 2 3 4 5; do chaos_post_attempt now false; assert_eq "POST during the broker outage" 201 "$LAST_STATUS"; done
T10="$LAST_OCCURRED_AT"
sleep 2
BACKLOG_USER="$(outbox_backlog_user "$USER_ID")"
assert_ge "outbox rows of the user unpublished during the outage" 1 "$BACKLOG_USER"
log "beacon_outbox_backlog_rows on /prometheus: $(prometheus_gauge beacon_outbox_backlog_rows || echo '?'); outbox-status: $(psql_q "SELECT count(*) || ' unpublished, oldest ' || coalesce(extract(epoch FROM (now() - min(created_at))), 0)::int || ' s' FROM attempt_event_outbox WHERE published_at IS NULL")"
log "docker compose start kafka (outage began $T_DOWN)"
compose start kafka
KAFKA_CID="$(compose ps -q kafka)"
pred_healthy() { [ "$(docker inspect --format '{{.State.Health.Status}}' "$1" 2>/dev/null)" = healthy ]; }
wait_for "kafka container healthy" 120 3 pred_healthy "$KAFKA_CID" || die "kafka did not become healthy"
pred_backlog_zero() { [ "$(outbox_backlog)" = 0 ]; }
wait_for "outbox backlog drained after the broker came back" 120 2 pred_backlog_zero || die "outbox backlog did not drain"
flink_wait_running "$JOB" 240 || die "stream job did not recover"
log "job $JOB RUNNING; exception history entries (restarts during the outage): $(flink "/jobs/$JOB/exceptions" | python3 -I -c 'import json,sys; print(len(json.load(sys.stdin)["exceptionHistory"]["entries"]))'); restored: $(flink_restored_id "$JOB")"
chaos_wait_stream_checksum "$USER_ID" "$(expected_checksum "$USER_ID" all)" 120 || die "STREAM row did not reach the 10-attempt checksum"
chaos_log_stream_row "$USER_ID"
chaos_result 10 "$T10" zero 0 "" $(chaos_expect_all_rev1)
