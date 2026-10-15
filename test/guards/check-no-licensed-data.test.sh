#!/usr/bin/env bash
# Self-test for scripts/ci/check-no-licensed-data.sh. Negative fixtures are generated at
# runtime in a temporary directory so nothing resembling licensed data is ever tracked.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
guard="$root/scripts/ci/check-no-licensed-data.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

pass=0
fail=0

expect() { # expect <0|1> <label> <paths...>
  local want="$1" label="$2"; shift 2
  local got=0
  (cd "$tmp" && bash "$guard" "$@" >"$tmp/out.log" 2>&1) || got=$?
  if [ "$got" -eq "$want" ]; then
    pass=$((pass + 1)); echo "ok   - $label (exit $got)"
  else
    fail=$((fail + 1)); echo "FAIL - $label (want exit $want, got $got)"; sed 's/^/       /' "$tmp/out.log"
  fi
}

cd "$tmp"
printf '{"type":"manifest","schema_version":1,"source":"synthetic-demo","synthetic":true,"license_review_status":"SYNTHETIC"}\n{"type":"board","board_id":"synthetic-demo-1"}\n' > good-synthetic.jsonl
printf '{"type":"manifest","schema_version":1,"source":"synthetic-demo","synthetic":true,"license_review_status":"SYNTHETIC"}\n' > good-synthetic.ndjson
printf '{"type":"manifest","source":"example-board","synthetic":false,"license_review_status":"LOCAL_RESEARCH_ONLY"}\n' > bad-nonsynthetic.jsonl
printf '{"type":"manifest","source":"example-board","license_review_status":"LOCAL_RESEARCH_ONLY"}\n' > bad-missing-flag.jsonl
printf '{"type":"manifest","synthetic":"true"}\n' > bad-string-flag.jsonl
printf 'not json at all\n' > bad-no-manifest.ndjson
printf '[]\n' > bad-array-manifest.jsonl
printf 'uuid,angle,grade\n' > bad-table.csv
printf 'x' > bad-weights.pt
printf 'x' > bad-weights.pth
printf 'x' > bad-columns.parquet
printf 'x' > bad-raw.db
printf 'x' > bad-raw.sqlite
printf 'x' > bad-raw.sqlite3
printf 'x' > bad-shadow.SQLITE.bak
printf 'SQLite format 3\000rest' > kilter.bin
printf 'x' > weights.safetensors
printf 'x' > model.onnx
printf 'x' > model.pkl
printf 'x' > cat.jsonl.gz
printf 'x' > arrays.npz
printf 'x' > bundle.zip
printf '\037\213\010rest' > routes.dat
printf 'x' > weights.ckpt
printf 'x' > model.gguf
printf 'readme\n' > fine.md
printf '{"a":1}\n' > fine.json

expect 0 "synthetic jsonl and ndjson plus ordinary files pass" good-synthetic.jsonl good-synthetic.ndjson fine.md fine.json
expect 1 "jsonl with synthetic:false rejected" bad-nonsynthetic.jsonl
expect 1 "jsonl without synthetic flag rejected" bad-missing-flag.jsonl
expect 1 "jsonl with synthetic as string rejected" bad-string-flag.jsonl
expect 1 "ndjson without JSON manifest rejected" bad-no-manifest.ndjson
expect 1 "jsonl whose manifest is not an object rejected" bad-array-manifest.jsonl
expect 1 "csv rejected" bad-table.csv
expect 1 "pt rejected" bad-weights.pt
expect 1 "pth rejected" bad-weights.pth
expect 1 "parquet rejected" bad-columns.parquet
expect 1 "db rejected" bad-raw.db
expect 1 "sqlite rejected" bad-raw.sqlite
expect 1 "sqlite3 rejected" bad-raw.sqlite3
expect 1 "sqlite anywhere in name rejected" bad-shadow.SQLITE.bak
expect 1 "one bad file among good ones fails the whole run" good-synthetic.jsonl fine.md bad-table.csv
expect 1 "sqlite header under another name rejected" kilter.bin
expect 1 "safetensors rejected" weights.safetensors
expect 1 "onnx rejected" model.onnx
expect 1 "pickle rejected" model.pkl
expect 1 "gzipped jsonl rejected" cat.jsonl.gz
expect 1 "npz rejected" arrays.npz
expect 1 "zip rejected" bundle.zip
expect 1 "gzip header under another name rejected" routes.dat
expect 1 "ckpt rejected" weights.ckpt
expect 1 "gguf rejected" model.gguf
expect 0 "uppercase extension of allowed file passes" fine.md

echo "guard self-test: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
