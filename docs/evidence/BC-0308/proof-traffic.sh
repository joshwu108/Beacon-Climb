#!/bin/bash
# Synthetic proof traffic for BC-0308: one throwaway user, consent, preferences, N attempts.
set -u
API=http://127.0.0.1:8080
W=$(mktemp -d); chmod 700 "$W"; trap 'rm -rf "$W"' EXIT
U="obs_$(date +%s | tail -c 7)_$RANDOM"
req() { curl -sS -o "$W/out" -w '%{http_code}' -X "$1" -H 'Accept: application/json' ${TOKEN:+-H "Authorization: Bearer $TOKEN"} ${3:+-H 'Content-Type: application/json' --data-binary "$3"} ${4:+-H "Idempotency-Key: $4"} "$API$2"; }
TOKEN=
s=$(req POST /v1/auth/register "{\"username\":\"$U\",\"password\":\"obs-long-password-1\"}"); echo "register $U -> $s"
TOKEN=$(python3 -I -c 'import json,sys; print(json.load(open(sys.argv[1]))["token"])' "$W/out")
s=$(req GET /v1/consent/current); echo "consent/current -> $s"
body=$(python3 -I -c 'import json,sys; d=json.load(open(sys.argv[1])); print(json.dumps({"consent_version": d["version"], "scopes": {"service": True, "research": False}, "text_sha256": d["text_sha256"]}))' "$W/out")
s=$(req POST /v1/me/consent "$body"); echo "consent -> $s"
s=$(req PUT /v1/me/preferences '{"board_id":"synthetic-demo-1","angle_deg":40,"initial_grade_band":"INTERMEDIATE","goals":["TECHNIQUE"]}'); echo "preferences -> $s"
s=$(req GET "/v1/routes?board_id=synthetic-demo-1&angle_deg=40&limit=5"); echo "routes -> $s"
RV=$(python3 -I -c 'import json,sys; d=json.load(open(sys.argv[1])); items=d.get("items") or d; print(items[0]["route_variant_id"])' "$W/out" 2>/dev/null) || { echo "could not read a route id:"; head -c 300 "$W/out"; echo; exit 1; }
N=${1:-6}
for i in $(seq 1 "$N"); do
  t=$(date -u -v-${i}M +%Y-%m-%dT%H:%M:%SZ)
  s=$(req POST /v1/me/attempts "{\"route_variant_id\":\"$RV\",\"occurred_at\":\"$t\",\"attempts_count\":$((1 + i % 3)),\"sent\":$([ $((i % 2)) = 0 ] && echo true || echo false)}" "obs-$U-$i"); echo "attempt $i -> $s"
  s=$(req GET "/v1/me/attempts?limit=5"); echo "list attempts -> $s"
  sleep 1.5
done
s=$(req GET /v1/me); echo "me -> $s"
s=$(req GET /v1/me/attempts/00000000-0000-0000-0000-000000000000); echo "missing attempt (expect 404) -> $s"
echo "traffic done user=$U route=$RV"
