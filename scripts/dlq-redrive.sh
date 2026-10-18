#!/usr/bin/env bash
# BC-0302: operator CLI for stream-dlq.v1, run on the laptop against the compose Kafka host
# listener (127.0.0.1:9094). Wraps the api jar's `redrive` Spring profile (DlqRedriveApplication:
# no datasource, no Flyway, no web server; Postgres is never touched). Run via the Makefile:
#   make dlq-status [TOPIC=stream-dlq.v1]
#   make dlq-redrive EVENTS=<id,id,...>|ALL [DRY_RUN=1] [TOPIC=stream-dlq.v1]
# Direct use: scripts/dlq-redrive.sh --list | --events <ids|ALL> [--dry-run] [--topic <topic>]
#
# Reads exactly one value from .env (BEACON_STREAM_BOOTSTRAPSERVERS, optional) in a subshell; the
# database passwords in .env are never exported to the JVM. Exit codes are the profile's: 0 every
# selected record published (or dry run / list), 1 at least one record skipped, 2 usage error.
# Authorisation: whoever can run this script on the host with the .env file can redrive; there is
# no HTTP endpoint and no further gate (docs/runbooks/dlq.md).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

API_JAR="services/api/build/libs/api-0.1.0.jar"
DEFAULT_BOOTSTRAP="127.0.0.1:9094"

usage() {
  echo "usage: $0 --list [--topic <topic>] | --events <id,id,...>|ALL [--dry-run] [--topic <topic>]" >&2
  exit 2
}

LIST=0
EVENTS=""
DRY_RUN=0
TOPIC=""
while [ $# -gt 0 ]; do
  case "$1" in
    --list) LIST=1 ;;
    --events) [ $# -ge 2 ] || usage; EVENTS="$2"; shift ;;
    --events=*) EVENTS="${1#--events=}" ;;
    --dry-run) DRY_RUN=1 ;;
    --topic) [ $# -ge 2 ] || usage; TOPIC="$2"; shift ;;
    --topic=*) TOPIC="${1#--topic=}" ;;
    -h|--help) usage ;;
    *) echo "dlq-redrive: unknown argument '$1'" >&2; usage ;;
  esac
  shift
done
if [ "$LIST" -eq 0 ] && [ -z "$EVENTS" ]; then
  usage
fi
[ -f .env ] || { echo "dlq-redrive: missing .env (copy .env.example and set every value)" >&2; exit 2; }
[ -f "$API_JAR" ] || { echo "dlq-redrive: missing $API_JAR - run ./gradlew :api:bootJar (make dlq-status / dlq-redrive do)" >&2; exit 2; }

# One optional variable from .env, read in a subshell so nothing else is exported.
BOOTSTRAP="${BEACON_STREAM_BOOTSTRAPSERVERS:-$(set +u; . ./.env >/dev/null 2>&1; printf '%s' "${BEACON_STREAM_BOOTSTRAPSERVERS:-}")}"
BOOTSTRAP="${BOOTSTRAP:-$DEFAULT_BOOTSTRAP}"

ARGS=(--spring.profiles.active=redrive "--beacon.stream.bootstrap-servers=$BOOTSTRAP")
if [ "$LIST" -eq 1 ]; then
  ARGS+=(--redrive.list=true)
else
  ARGS+=("--redrive.events=$EVENTS")
  [ "$DRY_RUN" -eq 1 ] && ARGS+=(--redrive.dry-run=true)
fi
[ -n "$TOPIC" ] && ARGS+=("--redrive.topic=$TOPIC")

exec "${JAVA_HOME:+$JAVA_HOME/bin/}java" -jar "$API_JAR" "${ARGS[@]}"
