#!/usr/bin/env bash
# BC-0306: the batch parity oracle of ADR-0004, run on the laptop against the compose Postgres
# (127.0.0.1:5432). Wraps the api jar's `parity` Spring profile (ParityRunner: no web server, no
# Kafka, no Flyway; SELECT only as the runtime role beacon_app). Run via the Makefile:
#   make test-parity [USER=<uuid>|all] [OUT=docs/evidence/BC-0306/parity-<timestamp>.json]
# Direct use: scripts/parity/parity.sh [--user <uuid>|all] [--out <path>] [--commit <sha>]
#
# Reads exactly one value from .env (BEACON_APP_DB_PASSWORD) in a subshell; the migration and
# superuser passwords are never exported to the JVM. The commit recorded in the report is
# --commit, else BEACON_GIT_COMMIT, else `git rev-parse HEAD`, else "unknown". Exit codes are the
# profile's: 0 every compared user EQUAL, 1 at least one DIFF or SAME_W_MISMATCH, 2 usage error,
# failed run, or the synthetic-only refusal (no report written). Report format:
# scripts/parity/README.md.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

API_JAR="services/api/build/libs/api-0.1.0.jar"
DEFAULT_DB_URL="jdbc:postgresql://127.0.0.1:5432/beacon"

usage() {
  echo "usage: $0 [--user <uuid>|all] [--out <path>] [--commit <sha>]" >&2
  exit 2
}

USER_ARG="all"
OUT=""
COMMIT=""
while [ $# -gt 0 ]; do
  case "$1" in
    --user) [ $# -ge 2 ] || usage; USER_ARG="$2"; shift ;;
    --user=*) USER_ARG="${1#--user=}" ;;
    --out) [ $# -ge 2 ] || usage; OUT="$2"; shift ;;
    --out=*) OUT="${1#--out=}" ;;
    --commit) [ $# -ge 2 ] || usage; COMMIT="$2"; shift ;;
    --commit=*) COMMIT="${1#--commit=}" ;;
    -h|--help) usage ;;
    *) echo "parity: unknown argument '$1'" >&2; usage ;;
  esac
  shift
done
[ -f .env ] || { echo "parity: missing .env (copy .env.example and set every value)" >&2; exit 2; }
[ -f "$API_JAR" ] || { echo "parity: missing $API_JAR - run ./gradlew :api:bootJar (make test-parity does)" >&2; exit 2; }

# One variable from .env, read in a subshell so nothing else is exported.
APP_PASSWORD="${BEACON_APP_DB_PASSWORD:-$(set +u; . ./.env >/dev/null 2>&1; printf '%s' "${BEACON_APP_DB_PASSWORD:-}")}"
[ -n "$APP_PASSWORD" ] || { echo "parity: BEACON_APP_DB_PASSWORD is not set in .env" >&2; exit 2; }

if [ -z "$COMMIT" ]; then
  COMMIT="${BEACON_GIT_COMMIT:-$(git rev-parse HEAD 2>/dev/null || printf 'unknown')}"
fi

ARGS=(--spring.profiles.active=parity "--parity.user=$USER_ARG" "--parity.commit=$COMMIT")
[ -n "$OUT" ] && ARGS+=("--parity.out=$OUT")

BEACON_DB_URL="${BEACON_DB_URL:-$DEFAULT_DB_URL}" \
BEACON_APP_DB_PASSWORD="$APP_PASSWORD" \
BEACON_FLYWAY_ENABLED=false \
exec "${JAVA_HOME:+$JAVA_HOME/bin/}java" -jar "$API_JAR" "${ARGS[@]}"
