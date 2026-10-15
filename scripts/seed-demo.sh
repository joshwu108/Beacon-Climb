#!/usr/bin/env bash
# BC-0120: seed the running compose stack (`make dev`) with the synthetic demo board, a
# consented `demo` user and a local `admin` (role ADMIN). Idempotent: every step either
# creates or confirms, and a re-run exits 0. Run via `make seed-demo` from the repo root.
#
# Never prints a secret: tokens stay in files under a private temp dir, the admin password
# is read from .env (BEACON_ADMIN_PASSWORD) in a subshell and passed to python through the
# environment of that single call. Only demo/demo-password (public contract fixture) is
# echoed.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

API_URL="${BEACON_API_URL:-http://127.0.0.1:8080}"
WEB_URL="${BEACON_WEB_URL:-http://127.0.0.1:3000}"
BOARD_FILE="ml/datasets/synthetic/fixtures/demo-board-v1.jsonl"
API_JAR="services/api/build/libs/api-0.1.0.jar"
DB_URL="${BEACON_DB_URL:-jdbc:postgresql://127.0.0.1:5432/beacon}"
READY_TIMEOUT_S=60
COMPOSE=(docker compose --env-file .env -f infra/compose/docker-compose.yml --profile core)

DEMO_USER="demo"
DEMO_PASSWORD="demo-password"
ADMIN_USER="admin"

log() { printf 'seed-demo: %s\n' "$*"; }
die() { printf 'seed-demo: ERROR: %s\n' "$*" >&2; exit 1; }

for tool in curl python3 docker; do
  command -v "$tool" >/dev/null 2>&1 || die "$tool is required on PATH"
done
[ -f .env ] || die "missing .env (copy .env.example and set every value)"
[ -f "$BOARD_FILE" ] || die "missing $BOARD_FILE"

# Read one variable from .env without exporting the rest into this process.
ADMIN_PASSWORD="$(set +u; . ./.env >/dev/null 2>&1; printf '%s' "${BEACON_ADMIN_PASSWORD:-}")"
[ -n "$ADMIN_PASSWORD" ] || die "BEACON_ADMIN_PASSWORD is unset or empty in .env (generate one with: openssl rand -hex 24)"
[ "${#ADMIN_PASSWORD}" -ge 8 ] || die "BEACON_ADMIN_PASSWORD must be at least 8 characters"

WORK="$(mktemp -d)"
chmod 700 "$WORK"
trap 'rm -rf "$WORK"' EXIT

# json_get FILE KEY -> prints the top-level string/number value of KEY.
json_get() {
  python3 -I -c 'import json,sys; v=json.load(open(sys.argv[1]))[sys.argv[2]]; print(v)' "$1" "$2"
}
# credentials_json USER -> JSON body {"username","password"} using $PW from the environment.
credentials_json() {
  python3 -I -c 'import json,os,sys; print(json.dumps({"username": sys.argv[1], "password": os.environ["PW"]}))' "$1"
}
# request METHOD PATH OUTFILE [TOKENFILE] [BODYFILE] -> prints the HTTP status.
request() {
  local method="$1" path="$2" out="$3" tokenfile="${4:-}" bodyfile="${5:-}"
  local args=(-sS -o "$out" -w '%{http_code}' -X "$method" -H 'Accept: application/json')
  if [ -n "$tokenfile" ]; then args+=(-H "@$tokenfile"); fi
  if [ -n "$bodyfile" ]; then args+=(-H 'Content-Type: application/json' --data-binary "@$bodyfile"); fi
  curl "${args[@]}" "$API_URL$path"
}
# authenticate USER TOKENFILE: register (201) or, when the username exists (409), log in.
# $PW must hold the password. Writes an `Authorization: Bearer ...` header line to TOKENFILE.
authenticate() {
  local user="$1" tokenfile="$2" status
  local body="$WORK/$user.body.json" resp="$WORK/$user.resp.json"
  credentials_json "$user" >"$body"
  status="$(request POST /v1/auth/register "$resp" "" "$body")"
  case "$status" in
    201) log "user '$user' registered" ;;
    409)
      status="$(request POST /v1/auth/session "$resp" "" "$body")"
      [ "$status" = 201 ] || die "login as '$user' failed with HTTP $status: $(cat "$resp")"
      log "user '$user' already exists; logged in"
      ;;
    *) die "register '$user' failed with HTTP $status: $(cat "$resp")" ;;
  esac
  printf 'Authorization: Bearer %s\n' "$(json_get "$resp" token)" >"$tokenfile"
  rm -f "$body" "$resp"
}

# 1. Wait for the API.
log "waiting for $API_URL/health/ready (up to ${READY_TIMEOUT_S}s)"
for ((i = 0; i < READY_TIMEOUT_S; i++)); do
  if curl -fsS -o /dev/null "$API_URL/health/ready"; then break; fi
  sleep 1
done
curl -fsS -o /dev/null "$API_URL/health/ready" || die "API not ready after ${READY_TIMEOUT_S}s; run 'make dev' first"
log "API ready"

# 2. Import the synthetic demo board on the host JVM, exactly like `make import` (same jar,
#    .env secrets, BEACON_DB_URL on the published port) but WITHOUT its `:api:bootJar`
#    step: that would rewrite the jar bind-mounted into the running api container, and a
#    JVM whose jar changes underneath it fails lazy class loading (NoClassDefFoundError,
#    hung /v1 requests) until `docker compose restart api`. The importer prints
#    `imported routes=N ... provenance=...` then `updated ...`; a re-run inserts 0 rows.
[ -f "$API_JAR" ] || die "missing $API_JAR; run 'make dev' first"
log "importing $BOARD_FILE"
if ! (
  set -a; . ./.env; set +a
  BEACON_DB_URL="$DB_URL" exec "${JAVA_HOME:+$JAVA_HOME/bin/}java" -jar "$API_JAR" \
    --spring.profiles.active=import "--import.file=$BOARD_FILE"
) >"$WORK/import.log" 2>"$WORK/import.err"; then
  tail -n 20 "$WORK/import.err" >&2
  die "catalog import failed (see above; the importer needs a JDK 21 on PATH or JAVA_HOME)"
fi
grep -E '^(imported|updated) ' "$WORK/import.log" || { cat "$WORK/import.log"; die "importer printed no summary line"; }

# 3. demo user: account, consent (current version + text hash), preferences.
PW="$DEMO_PASSWORD" authenticate "$DEMO_USER" "$WORK/demo.token"
status="$(request GET /v1/consent/current "$WORK/consent.json")"
[ "$status" = 200 ] || die "GET /v1/consent/current failed with HTTP $status"
python3 -I -c '
import json, sys
doc = json.load(open(sys.argv[1]))
body = {"consent_version": doc["version"], "scopes": {"service": True, "research": False}, "text_sha256": doc["text_sha256"]}
json.dump(body, open(sys.argv[2], "w"))
' "$WORK/consent.json" "$WORK/consent.body.json"
status="$(request POST /v1/me/consent "$WORK/consent.resp" "$WORK/demo.token" "$WORK/consent.body.json")"
[ "$status" = 204 ] || die "POST /v1/me/consent failed with HTTP $status: $(cat "$WORK/consent.resp")"
log "consent $(json_get "$WORK/consent.json" version) recorded for '$DEMO_USER' (service=true, research=false)"

printf '%s' '{"board_id":"synthetic-demo-1","angle_deg":40,"initial_grade_band":"INTERMEDIATE","goals":["TECHNIQUE"]}' >"$WORK/prefs.body.json"
status="$(request PUT /v1/me/preferences "$WORK/prefs.resp" "$WORK/demo.token" "$WORK/prefs.body.json")"
[ "$status" = 200 ] || die "PUT /v1/me/preferences failed with HTTP $status: $(cat "$WORK/prefs.resp")"
log "preferences set for '$DEMO_USER': board synthetic-demo-1 at 40 deg, INTERMEDIATE, goals [TECHNIQUE]"

status="$(request GET /v1/me "$WORK/me.json" "$WORK/demo.token")"
[ "$status" = 200 ] || die "GET /v1/me failed with HTTP $status"
log "GET /v1/me ok: username=$(json_get "$WORK/me.json" username)"

# 4. admin: account from BEACON_ADMIN_PASSWORD, then role ADMIN in SQL (the only way to set
#    a role; ADR-0005). psql runs inside the postgres container as its superuser.
PW="$ADMIN_PASSWORD" authenticate "$ADMIN_USER" "$WORK/admin.token"
unset ADMIN_PASSWORD
"${COMPOSE[@]}" exec -T postgres psql -U beacon -d beacon -v ON_ERROR_STOP=1 -qAt \
  -c "UPDATE users SET role = 'ADMIN' WHERE username = '$ADMIN_USER' AND role <> 'ADMIN';" >/dev/null
role="$("${COMPOSE[@]}" exec -T postgres psql -U beacon -d beacon -v ON_ERROR_STOP=1 -qAt \
  -c "SELECT role FROM users WHERE username = '$ADMIN_USER';")"
[ "$role" = "ADMIN" ] || die "expected role ADMIN for '$ADMIN_USER', got '${role:-<none>}'"
log "user '$ADMIN_USER' has role ADMIN"

cat <<SUMMARY

Beacon Climb dev stack is seeded.
  web  $WEB_URL
  api  $API_URL
  sign in as  $DEMO_USER / $DEMO_PASSWORD   (board synthetic-demo-1, 40 deg)
  admin user  $ADMIN_USER (password: BEACON_ADMIN_PASSWORD in .env; not printed)
Re-running 'make seed-demo' is safe.
SUMMARY
