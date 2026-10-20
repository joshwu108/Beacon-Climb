#!/usr/bin/env bash
# BC-0307 scenario flink-taskmanager-restart (ADR-0004 "Flink restart after checkpoint"): ten
# attempts, wait until at least one more checkpoint completed, `docker compose restart
# taskmanager`, wait until the job is RUNNING again with a restored checkpoint id, five more
# attempts. The job resumes from the checkpointed Kafka offsets and MapState; records it re-emits
# for already-materialized events arrive at higher feature-topic offsets and are applied again
# with the same content (at-least-once, ADR-0004 "A recovered job may briefly emit an older full
# snapshot at a newer offset"), and the first event after the restore sees no watermark (W =
# occurred_at). Asserts: restored id present, job RUNNING, STREAM row covers 15, parity zero.
set -euo pipefail
export CHAOS_SCENARIO="${CHAOS_SCENARIO:-flink-taskmanager-restart}"
. "$(cd "$(dirname "${BASH_SOURCE[0]}")/../../test/chaos/stream" && pwd)/lib.sh"
chaos_guard_base_url
chaos_work_dir

chaos_new_user tmrst
for i in 1 2 3 4 5 6 7 8 9 10; do chaos_post_attempt now "$([ $((i % 3)) = 0 ] && echo false || echo true)"; done
chaos_wait_stream_checksum "$USER_ID" "$(expected_checksum "$USER_ID" all)" 90 || die "STREAM row did not reach the 10-attempt checksum"
chaos_log_stream_row "$USER_ID"
JOB="$(flink_running_job)"; [ -n "$JOB" ] || die "no RUNNING job"
N0="$(flink_checkpoints_completed "$JOB")"
pred_checkpoint_advanced() { [ "$(flink_checkpoints_completed "$1")" -gt "$2" ]; }
wait_for "a checkpoint completed after the 10 attempts (> $N0)" 60 2 pred_checkpoint_advanced "$JOB" "$N0" || die "no new checkpoint"
log "checkpoints completed: $(flink_checkpoints_completed "$JOB"); restored before: $(flink_restored_id "$JOB")"
log "docker compose restart taskmanager"
compose restart taskmanager
flink_wait_running "$JOB" 180 || die "job did not return to RUNNING"
RESTORED="$(flink_restored_id "$JOB")"
[ -n "$RESTORED" ] || die "job RUNNING but no restored checkpoint id"
log "job $JOB RUNNING again, latest restored checkpoint id: $RESTORED; exception history entries: $(flink "/jobs/$JOB/exceptions" | python3 -I -c 'import json,sys; print(len(json.load(sys.stdin)["exceptionHistory"]["entries"]))')"
for i in 1 2 3 4 5; do chaos_post_attempt now true; done
T15="$LAST_OCCURRED_AT"
chaos_wait_stream_checksum "$USER_ID" "$(expected_checksum "$USER_ID" all)" 120 || die "STREAM row did not reach the 15-attempt checksum after the restore"
chaos_log_stream_row "$USER_ID"
log "restored_checkpoint_id=$RESTORED"
chaos_result 15 "$T15" zero 0 "" $(chaos_expect_all_rev1)
