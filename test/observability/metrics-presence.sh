#!/bin/bash
# BC-0308: asserts that the api's Prometheus scrape (/prometheus on 127.0.0.1:8080, BC-0301)
# exposes the metric names the dashboard and the alert rules depend on. Run through
# `make observability-check` (STREAM=0 when only the core profile is up) or directly:
#   bash test/observability/metrics-presence.sh
# Environment: BEACON_API_URL (default http://127.0.0.1:8080), BEACON_OBS_STREAM=0 to skip the
# stream-profile meters (materializer and Kafka consumer, which only exist with kafka.yml loaded),
# BEACON_PROMETHEUS_URL (default http://127.0.0.1:9090; set to "" to skip the targets check).
# Exit 0 only when every required name is present; prints FOUND/MISSING per name, then the
# Prometheus target health when Prometheus is reachable. Never prints a secret: the scrape is
# unauthenticated and carries no user data.
set -u

API_URL="${BEACON_API_URL:-http://127.0.0.1:8080}"
PROM_URL="${BEACON_PROMETHEUS_URL-http://127.0.0.1:9090}"
STREAM="${BEACON_OBS_STREAM:-1}"

# Always present once the actuator and the outbox beans exist (OutboxMetrics registers its
# gauges and counters at construction, so they are on the scrape at 0 before any event).
REQUIRED_CORE=(
  http_server_requests_seconds_count
  http_server_requests_seconds_sum
  http_server_requests_seconds_max
  beacon_outbox_backlog_rows
  beacon_outbox_backlog_age_seconds
  beacon_outbox_stale_rows
  beacon_outbox_published_total
  beacon_outbox_publish_failures_total
)
# Present only while beacon.stream.enabled=true (kafka.yml): MaterializerMetrics counters and
# the Kafka client's consumer metrics registered by the MicrometerConsumerListener (BC-0305).
REQUIRED_STREAM=(
  beacon_materializer_applied_total
  beacon_materializer_skipped_total
  beacon_materializer_conflict_total
  beacon_materializer_errors_total
  kafka_consumer_fetch_manager_records_lag_max
)
# Registered lazily (MaterializerMetrics.invalid registers the {error_code} series on first use),
# so absent on a healthy stack; reported, never required.
OPTIONAL=(
  beacon_materializer_invalid_total
  beacon_dlq_published_total
  beacon_dlq_publish_failures_total
)

scrape="$(mktemp)"
trap 'rm -f "$scrape"' EXIT
if ! curl -fsS --max-time 10 -o "$scrape" "$API_URL/prometheus"; then
  echo "metrics-presence: cannot scrape $API_URL/prometheus (is the stack up? make stream-up / make dev)" >&2
  exit 1
fi
echo "metrics-presence: scraped $API_URL/prometheus ($(wc -l <"$scrape" | tr -d ' ') lines)"

missing=0
check() { # $1 name, $2 required(1|0)
  if grep -q "^$1\(\$\|[{ ]\)" "$scrape"; then
    echo "FOUND    $1 ($(grep -c "^$1\(\$\|[{ ]\)" "$scrape") series)"
  elif [ "$2" = 1 ]; then
    echo "MISSING  $1"; missing=$((missing + 1))
  else
    echo "ABSENT   $1 (optional: registered on first use)"
  fi
}
for m in "${REQUIRED_CORE[@]}"; do check "$m" 1; done
if [ "$STREAM" = 0 ]; then
  echo "metrics-presence: BEACON_OBS_STREAM=0, skipping the stream-profile meters"
else
  for m in "${REQUIRED_STREAM[@]}"; do check "$m" 1; done
fi
for m in "${OPTIONAL[@]}"; do check "$m" 0; done

# Histogram buckets are NOT enabled for http.server.requests (application.yaml sets no
# management.metrics.distribution.*), so the dashboard uses avg (_sum/_count) and _max. Say so
# here so a future change (BC-0308 part 2) is visible in the check output.
if grep -q '^http_server_requests_seconds_bucket' "$scrape"; then
  echo "INFO     http_server_requests_seconds_bucket present: histogram_quantile panels are possible"
else
  echo "INFO     http_server_requests_seconds_bucket absent (no percentiles-histogram yet; dashboard uses avg + max)"
fi

if [ -n "$PROM_URL" ]; then
  if targets="$(curl -fsS --max-time 10 "$PROM_URL/api/v1/targets" 2>/dev/null)"; then
    echo "metrics-presence: Prometheus targets ($PROM_URL):"
    printf '%s' "$targets" | python3 -I -c '
import json, sys
d = json.load(sys.stdin)["data"]["activeTargets"]
for t in sorted(d, key=lambda t: t["labels"]["job"]):
    print("  %-18s %-22s %-5s %s" % (t["labels"]["job"], t["scrapeUrl"], t["health"], t.get("lastError", "")))
'
  else
    echo "metrics-presence: Prometheus not reachable at $PROM_URL (make observability-up); targets not checked"
  fi
fi

if [ "$missing" -gt 0 ]; then
  echo "metrics-presence: FAIL, $missing required metric name(s) missing" >&2
  exit 1
fi
echo "metrics-presence: OK"
