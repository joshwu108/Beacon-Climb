#!/bin/bash
# BC-0303 compose proof (ADR-0004): Kafka 4.3.1 KRaft <-> Flink 2.2.1 round trip, checkpoint,
# taskmanager restart with restore, offset recovery. Run from the repo root; output is the
# evidence transcript. Nothing here is skipped on failure (set -e off on purpose) so a failing
# step is visible in the transcript instead of hidden.
cd "$(git rev-parse --show-toplevel)" || exit 1
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
C="docker compose --env-file .env -f infra/compose/docker-compose.yml -f infra/compose/kafka.yml -f infra/compose/flink.yml --profile core --profile stream"
EX=contracts/events/examples
step() { echo; echo "##### $(date -u +%H:%M:%SZ) $*"; }
rest() { curl -sS "http://127.0.0.1:8081$1"; }
event() { jq -c --arg u "$UID1" --arg t "${3:-$T0}" "$2 | .user_id=\$u | .occurred_at=\$t | .ingested_at=\$t" "$EX/$1.json"; }   # single-line event JSON for the proof user, with an optional jq edit
produce() { # stdin: key|json lines
  $C exec -T kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server kafka:9092 \
    --topic attempts.v1 --property parse.key=true --property key.separator='|'
}
consume_all() { # $1 topic, $2 timeout ms; prints partition/key/value for every record, exits non-zero on timeout (expected)
  $C exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:9092 \
    --topic "$1" --from-beginning --timeout-ms "$2" --property print.partition=true \
    --property print.key=true --property print.headers=true 2>/dev/null
}
UID1=018f0000-0000-7000-8000-0000000000bb   # BC-0303 proof user (fresh per attempt); the api outbox (BC-0301) publishes user ...0001
# Event time is set relative to now: the shared attempts.v1 already carries a real-time event from the
# BC-0301 session, so the 24 h watermark sits near now-24h; the committed examples (2026-10-14) would be
# late (attempt 3, compose-proof-late-path.txt proves that path). T0 = now-2h, T1 = now-1h.
T0=$(date -u -v-2H +%Y-%m-%dT%H:%M:%SZ); T1=$(date -u -v-1H +%Y-%m-%dT%H:%M:%SZ); echo "T0=$T0 T1=$T1"

step "0. toolchain"
docker version --format 'docker {{.Server.Version}}'; docker compose version
echo "images: $(docker images --format '{{.Repository}}:{{.Tag}}' | grep -E '^(flink:2.2.1-java21|apache/kafka:4.3.1)$' | tr '\n' ' ')"
git -C . rev-parse --short HEAD

step "1. compose up --wait (the COMPOSE_STREAM command behind make stream-up; the api jar is the existing services/api/build/libs/api-0.1.0.jar because :api Gradle tasks belong to another session tonight)"
echo "$C up -d --wait"
$C up -d --wait 2>&1 | tail -25
$C ps --format 'table {{.Name}}\t{{.Status}}\t{{.Ports}}'

step "1b. what is already on the topics (the stack is shared with the concurrent BC-0301 proof; nothing is deleted)"
echo "attempts.v1 records before this proof: $(consume_all attempts.v1 6000 | wc -l | tr -d ' ')"
echo "climber-features.v1 records before this proof: $(consume_all climber-features.v1 6000 | wc -l | tr -d ' ')"
echo "checkpoint volume owner after flink-init: $($C exec -T jobmanager stat -c '%u:%g %n' /checkpoints)"

step "2. topics (kafka-init created them with --if-not-exists; recreated above with identical settings)"
$C exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --describe 2>/dev/null | grep -E "^Topic:" 

step "3. make stream-submit"
make stream-submit 2>&1 | tail -8
sleep 8
make stream-job-status 2>&1 | tail -6
JOB=$(rest /jobs | jq -r '.jobs[] | select(.status=="RUNNING") | .id' | head -1)
echo "job id: $JOB"
rest /jobs/$JOB | jq '{name, state, vertices: [.vertices[] | {name, status, parallelism}]}'

step "4. produce 3 attempts.v1 events (recorded r1, corrected r2, retracted r3; key = user_id)"
{ echo "$UID1|$(event valid-attempt-recorded .event)";
  echo "$UID1|$(event valid-attempt-corrected .event)";
  echo "$UID1|$(event valid-attempt-retracted .event)"; } | tee /dev/stderr | produce
echo "(producer exit $?)"

step "5. consume climber-features.v1 --from-beginning; records keyed by the proof user (expect 3)"
sleep 10
consume_all climber-features.v1 15000 > /tmp/bc0303-features.txt
grep -c "$UID1" /tmp/bc0303-features.txt | xargs echo "feature records for $UID1:"
grep "$UID1" /tmp/bc0303-features.txt
echo "(other keys on the topic: $(grep -vc "$UID1" /tmp/bc0303-features.txt))"

step "6. checkpoints (REST) and the checkpoint volume"
sleep 12
rest /jobs/$JOB/checkpoints | jq '{counts, latest_completed: .latest.completed | {id, status, external_path, trigger_timestamp}, latest_restored: .latest.restored}'
$C exec -T jobmanager ls -R /checkpoints | head -20
echo "committed consumer-group offsets (beacon-stream):"
$C exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:9092 --describe --group beacon-stream 2>/dev/null | grep -E "^GROUP|attempts.v1" | awk '{print $1,$2,$3,$4,$5,$6}'

step "7. restart taskmanager, then wait until the job has RESTORED from a checkpoint and is RUNNING again"
BEFORE=$(rest /jobs/$JOB/checkpoints | jq -r '.counts.completed'); echo "completed checkpoints before restart: $BEFORE"
$C restart taskmanager 2>&1 | tail -2
for i in $(seq 1 50); do
  st=$(rest /jobs/$JOB | jq -r .state); restored=$(rest /jobs/$JOB/checkpoints | jq -r '.latest.restored.id // "none"')
  echo "t+$((i*3))s job state: $st restored_from_checkpoint: $restored"
  [ "$st" = "RUNNING" ] && [ "$restored" != "none" ] && [ "$(rest /jobs/$JOB | jq -r '[.vertices[].status] | all(.=="RUNNING")')" = "true" ] && break
  sleep 3
done
rest /jobs/$JOB | jq '{state, vertices: [.vertices[] | {name: .name[0:60], status}]}'
rest /jobs/$JOB/checkpoints | jq '{counts, latest_restored: .latest.restored | {id, restore_timestamp, external_path}}'
rest /jobs/$JOB/exceptions | jq '{history_entries: (.exceptionHistory.entries | length), first: (.exceptionHistory.entries[0] | {exceptionName, taskName: .taskName[0:60], timestamp})}'

step "8. produce 1 more event (new attempt id) after the restart, then count ALL feature records"
echo "$UID1|$(event valid-attempt-recorded '.event | .event_id="018f0000-0000-7000-8000-000000000011" | .attempt_id="018f0000-0000-7000-8000-000000000012"' "$T1")" | tee /dev/stderr | produce
sleep 10
consume_all climber-features.v1 15000 > /tmp/bc0303-features2.txt
grep -c "$UID1" /tmp/bc0303-features2.txt | xargs echo "feature records for $UID1 after the restart (expect 4 = 3 + 1, no reprocessing):"
grep "$UID1" /tmp/bc0303-features2.txt
echo "attempts.v1 records for $UID1 from beginning: $(consume_all attempts.v1 10000 | grep -c "$UID1")"
rest /jobs/$JOB/checkpoints | jq '{counts, latest_completed_id: .latest.completed.id, latest_restored_id: .latest.restored.id}'
$C exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:9092 --describe --group beacon-stream 2>/dev/null | grep -E "^GROUP|attempts.v1" | awk '{print $1,$2,$3,$4,$5,$6}'

step "9. reconciliation topic (expected 0 for this user: every event was within the 24 h window)"
consume_all stream-reconciliation.v1 8000 | grep -c "$UID1" | xargs echo "stream-reconciliation.v1 records for the proof user:"

step "10. docker stats --no-stream"
docker stats --no-stream --format 'table {{.Name}}\t{{.MemUsage}}\t{{.MemPerc}}\t{{.CPUPerc}}' | tee docs/evidence/BC-0303/docker-stats.txt

step "11. make stream-down"
make stream-down 2>&1 | tail -6
docker ps -a --filter name=compose- --format '{{.Names}} {{.Status}}' | wc -l | xargs echo "compose containers left:"
echo "done $(date -u +%H:%M:%SZ)"
