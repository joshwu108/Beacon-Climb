# Observability: Prometheus, Grafana, Tempo (`make observability-up`)

**Last real run:** 2026-10-18, working tree of BC-0308 part 1 (uncommitted at the time of
writing) on the reference laptop (Apple Silicon, Docker Desktop, 7.6 GB for containers), full
stack: `make stream-up && make stream-submit && make observability-up`, then the proof in
section 6. Everything observed was synthetic (one throwaway `obs_*` user on the synthetic demo
board). Evidence: `docs/evidence/BC-0308/`.

Ticket BC-0308 is split: **part 1 (this runbook)** is the infrastructure, metrics scrape,
dashboard, alert rules and trace plumbing; **part 2 (api follow-up, not started)** adds the
`request_id` / `event_id` span attributes, the `http.server.requests` histogram buckets and a
server-side feature-freshness gauge. Section 7 lists exactly what part 2 owes.

## 1. Purpose and trigger

Open this runbook when:

- you want to see what the streaming slice is doing (outbox backlog, materializer outcomes,
  consumer lag, Flink checkpoints, DLQ) on one screen instead of `make outbox-status`,
  `make dlq-status` and the Flink UI one by one;
- a Prometheus rule in `infra/observability/prometheus/alerts.yml` is firing
  (`http://127.0.0.1:9090/alerts` or the "Alerts firing" tile of the dashboard);
- you need the trace of one API request (today: by service and route; by `request_id` once
  part 2 lands);
- `make observability-up` fails.

What runs (profile `observability`, `infra/compose/observability.yml`, merged last over the
stream file set):

| Service | Image (pinned) | Host port (127.0.0.1 only) | Role | `mem_limit` |
|---|---|---|---|---|
| `prometheus` | `prom/prometheus:v3.15.0` | 9090 | scrapes `api:8080/prometheus`, `jobmanager:9249`, `taskmanager:9249` and itself every 5 s; evaluates `alerts.yml`; 2 d retention on volume `prometheus-data` | 512m |
| `grafana` | `grafana/grafana:13.2.4` | **3001** (3000 is the web app) | provisioned datasources Prometheus (`uid: prometheus`) + Tempo (`uid: tempo`) and the dashboard `beacon-streaming`; anonymous access off, sign-up off, one account `admin` | 512m |
| `tempo` | `grafana/tempo:3.0.4` | 3200 (query API only; OTLP 4317/4318 in-network only) | stores the traces the agents export; local blocks on volume `tempo-data`, 48 h retention; metrics-generator off | 512m |
| `otel-agent-init` | `curlimages/curl:8.22.0` | – | one-shot: downloads `opentelemetry-javaagent.jar` **v2.32.0** into volume `otel-agent` if missing, verifies sha256 against the pinned value and the release's `checksums.txt`, exits 0 | 128m |
| `api` (override) | – | – | gets the jar read-only at `/otel`, `JAVA_TOOL_OPTIONS=-javaagent:...`, `OTEL_SERVICE_NAME=beacon-api`, OTLP http/protobuf to `tempo:4318`, traces only (`OTEL_METRICS_EXPORTER=none`, logs none), `always_on` sampler. Still `nobody`, read-only root, `/tmp` tmpfs: the agent needs no writable directory | unchanged (1g) |
| `jobmanager` / `taskmanager` (override) | – | – | same agent through `FLINK_ENV_JAVA_OPTS_JM` / `_TM` (service names `beacon-stream-jobmanager` / `-taskmanager`), plus the Flink `PrometheusReporter` on 9249 through `-D metrics.reporter.prom.*` dynamic properties appended to the entrypoint command | jobmanager raised to 896m under this profile (section 5) |

Not in this stack, on purpose: no Kafka JMX exporter (broker metrics are not needed for the
dashboard; the consumer-side lag comes from the clients), no OpenTelemetry Collector (the
agents export straight to Tempo), no Alertmanager or any notification channel (section 4).

Image tags were checked against Docker Hub on 2026-10-18 (`v3.15.0` pushed 2026-09-25,
`13.2.4` 2026-10-13, `3.0.4` 2026-10-16, `8.22.0` 2026-09-02); the agent version is the
`releases/latest` redirect of `open-telemetry/opentelemetry-java-instrumentation` on that day
(v2.32.0, 2026-10-03) and its sha256 is the one in the release's `checksums.txt`.

## 2. Preconditions

- Docker Desktop with at least 7.5 GB for containers (section 5) and the core/stream stack
  running: `make stream-up` (or `make dev` for the api-only view: the Flink targets then show
  DOWN and the Flink row stays empty, nothing else breaks).
- `.env` with `GRAFANA_ADMIN_PASSWORD` set (`openssl rand -hex 24`, see `.env.example`).
  `make observability-up` refuses to start without it (`${GRAFANA_ADMIN_PASSWORD:?}`).
- Network access to `github.com` the first time (the agent jar download, 26 MB). Afterwards
  the jar lives in the `otel-agent` volume; `make reset-db` does not remove it
  (`docker volume rm compose_otel-agent` does).
- JDK 21 on the host only for `make stream-submit` (as for the stream profile).

## 3. Steps

### 3.1 Bring it up

```sh
make stream-up            # core + Kafka + Flink (or make dev for core only)
make observability-up     # prometheus, tempo, grafana, otel-agent-init; RECREATES api, jobmanager, taskmanager with the agent
make stream-submit        # (again) the recreated jobmanager has no job
```

Expected tail of `make observability-up`:

```
 Container compose-otel-agent-init-1 Exited
 Container compose-prometheus-1 Healthy
 Container compose-grafana-1 Healthy
 Container compose-api-1 Healthy
 Container compose-jobmanager-1 Healthy
 Container compose-taskmanager-1 Healthy
```

Order matters: `observability-up` recreates the api and both Flink roles (their environment
changes), which cancels a running Flink job, so submit the job after it, or run
`make observability-up` before `make stream-submit` from the start. Measured on the reference
laptop: `make observability-up` 25 s after the first run (the first run adds the image pulls
and the 26 MB agent download).

### 3.2 Check the scrape and the targets

```sh
make observability-check        # STREAM=0 when only `make dev` is running
```

Expected (docs/evidence/BC-0308/metrics-presence.txt):

```
FOUND    http_server_requests_seconds_count (11 series)
FOUND    beacon_outbox_backlog_rows (1 series)
...
FOUND    beacon_materializer_applied_total (1 series)
FOUND    kafka_consumer_fetch_manager_records_lag_max (24 series)
ABSENT   beacon_materializer_invalid_total (optional: registered on first use)
INFO     http_server_requests_seconds_bucket absent (no percentiles-histogram yet; dashboard uses avg + max)
metrics-presence: Prometheus targets (http://127.0.0.1:9090):
  beacon-api         http://api:8080/prometheus up
  flink-jobmanager   http://jobmanager:9249/metrics up
  flink-taskmanager  http://taskmanager:9249/metrics up
  prometheus         http://localhost:9090/metrics up
metrics-presence: OK
```

`ABSENT` lines are counters Micrometer registers on first use (`MaterializerMetrics.invalid`,
`DlqPublisher.published`): they appear after the first invalid record or DLQ publish and are
never required. The raw scrape: `curl -s http://127.0.0.1:8080/prometheus | grep ^beacon_`.

### 3.3 Open Grafana

`http://127.0.0.1:3001`, user `admin`, password `GRAFANA_ADMIN_PASSWORD` from `.env` (never
printed anywhere; the screenshot test reads it from the environment). Folder **Beacon**,
dashboard **Beacon streaming** (`/d/beacon-streaming`). It is provisioned from
`infra/observability/grafana/dashboards/beacon-streaming.json` and re-read every 30 s: edit
the file, not the UI (UI saves are disabled, `allowUiUpdates: false`).

Rows and panels, and what each metric means:

| Row | Panel | Metric(s) | Meaning |
|---|---|---|---|
| At a glance | API requests/s | `sum(rate(http_server_requests_seconds_count[1m]))` | all routes incl. the healthcheck (`/health/ready` every 3 s) and this scrape (`/prometheus` every 5 s) |
| | API 5xx ratio (5m) | 5xx count / all count | gate for `ApiErrorRate` (> 5 % for 5 min) |
| | Outbox backlog rows / stale rows | `beacon_outbox_backlog_rows`, `beacon_outbox_stale_rows` | unpublished `attempt_event_outbox` rows; stale = older than `stale-after` (60 s) |
| | Materializer lag | `max(kafka_consumer_fetch_manager_records_lag_max{job="beacon-api"})` | records the api's `climber-features.v1` consumer is behind (empty on the core profile) |
| | Alerts firing | `count(ALERTS{alertstate="firing"})` | rules currently firing (section 4) |
| API | Requests/s by status; latency **average** and **max** by route; 4xx/5xx by route | `http_server_requests_seconds_{count,sum,max}` | `avg = rate(sum)/rate(count)`; `max` is Micrometer's rolling-window maximum. **No p50/p95/p99**: `application.yaml` enables no `management.metrics.distribution.percentiles-histogram`, so there are no `_bucket` series and `histogram_quantile` is impossible. Part 2 (section 7) |
| Outbox | Backlog and stale rows; oldest unpublished age; published and failed per second | `beacon_outbox_backlog_age_seconds`, `beacon_outbox_published_total`, `beacon_outbox_publish_failures_total{reason}` | same numbers as `make outbox-status`; failures by `kafka`/`timeout`/`database`/`other` are rolled-back batches that retry on the next poll (at-least-once) |
| Materializer | Outcomes per second; errors; consumer lag (api + Flink source) | `beacon_materializer_{applied,skipped,conflict,invalid,errors}_total`, `kafka_consumer_fetch_manager_records_lag_max`, `flink_taskmanager_job_task_operator_KafkaSourceReader_KafkaConsumer_records_lag_max` | applied = STREAM snapshot row written; skipped `duplicate`/`stale`; conflict `checksum`/`partition` and invalid `{error_code}` went to the DLQ; errors = transaction/DLQ write failed, record redelivered. The Flink line is NaN (empty) until the client observed lag once |
| Flink | Job uptime; running jobs; checkpoints completed / failed; last checkpoint duration and size | `flink_jobmanager_job_uptime`, `flink_jobmanager_numRunningJobs`, `flink_jobmanager_job_numberOfCompletedCheckpoints`, `_numberOfFailedCheckpoints`, `_lastCheckpointDuration`, `_lastCheckpointSize` | job-scoped gauges exist only while the job runs (`job_name="beacon_climb_features"`); completed should climb ~6/min at the 10 s interval. Checkpoint *age* is not a Flink metric: `FlinkCheckpointStale` watches the counter's movement |
| Freshness and DLQ | Freshness proxy | `rate(beacon_materializer_applied_total[1m])` vs `rate(beacon_outbox_published_total[1m])` | **there is no server-side freshness metric yet.** End-to-end freshness (attempt commit to STREAM snapshot visible) is measured by `scripts/loadgen` (BC-0311: `make loadgen`, `make freshness`, p50/p95/p99 in `reports/loadgen/<run>/summary.txt`). Until an api gauge exists, applied keeping up with published, with flat lag panels, is the proxy |
| | DLQ records per 5 min | `increase(beacon_materializer_invalid_total)`, `increase(beacon_materializer_conflict_total)`, `increase(beacon_dlq_published_total{consumer})`, `increase(flink_taskmanager_job_task_operator_numRecordsIn{operator_name="sink_stream_dlq_v1:_Writer"})` | api-side and Flink-side writes to `stream-dlq.v1`. Flink reports the sink as two operators, `: Writer` and `: Committer`; only the Writer's `numRecordsIn` counts records (the Committer counts one committable per checkpoint; the first proof run alerted on it, fixed). Operator runbook: `docs/runbooks/dlq.md` |

Dashboard links (top left): Prometheus alerts and targets, the Flink UI, and an Explore query
for Tempo (`{ resource.service.name = "beacon-api" }`).

### 3.4 Find a trace

Today (part 1) traces are found by **service and route**: Explore, datasource Tempo, TraceQL

```
{ resource.service.name = "beacon-api" && name = "POST /v1/me/attempts" }
```

or from the host:

```sh
curl -s -G http://127.0.0.1:3200/api/search --data-urlencode 'q={ resource.service.name = "beacon-api" && name = "POST /v1/me/attempts" }' --data-urlencode limit=5
curl -s http://127.0.0.1:3200/api/traces/<traceID>
```

A `POST /v1/me/attempts` trace (docs/evidence/BC-0308/tempo-trace-post-attempts.txt) is one
Tomcat server span named after the route with `http.request.method`, `http.route`, `url.path`
and `http.response.status_code`, and the JDBC child spans of the transaction (`INSERT
beacon.attempts`, `INSERT beacon.attempt_revisions`, `INSERT beacon.attempt_event_outbox`,
`INSERT beacon.user_feature_snapshots`, ...). The agent also traces `OutboxPublisher.poll`
(Spring `@Scheduled`), the Kafka producer sends and the materializer consumer. SQL text in the
JDBC spans is the sanitized statement (parameters replaced by `?`); no request or response
body, header, token, note or user id is ever on a span, because nothing in the api sets span
attributes yet (part 2 adds `request_id` and `event_id` deliberately, nothing else).

Once part 2 lands: `{ span.request_id = "<X-Request-Id from the response or the api log>" }`
and `{ span.event_id = "<event_id from the DLQ record or the outbox row>" }`.

### 3.5 Bring it down

```sh
make observability-down   # removes prometheus, grafana, tempo, otel-agent-init; volumes kept
make stream-up            # or make dev: recreates api/jobmanager/taskmanager WITHOUT the agent
```

`observability-down` leaves the api and the Flink roles running with the agent still loaded
and an exporter that now cannot reach Tempo (it logs a connection failure every export
interval and drops the spans; the api keeps serving). Running `make stream-up` or `make dev`
afterwards recreates them without the agent. `make stream-down` stops everything; the
observability volumes (`prometheus-data`, `grafana-data`, `tempo-data`, `otel-agent`) survive
`make reset-db` too; `docker volume rm compose_<name>` drops one.

## 4. Alert rules (no notification channel)

`infra/observability/prometheus/alerts.yml`, evaluated every 15 s. **There is no
Alertmanager, no e-mail, no pager**: a firing rule is visible at
`http://127.0.0.1:9090/alerts` and on the dashboard's "Alerts firing" tile, nothing else.
Wiring a receiver is a deployment concern, not a laptop one. Thresholds are first guesses for
one laptop and the synthetic board.

| Rule | Expression (abridged) | Intent | First look |
|---|---|---|---|
| `OutboxBacklogStale` | `beacon_outbox_stale_rows > 0` for 2m | publisher stopped, broker unreachable or batches rolling back | `make outbox-status`, `... logs api \| grep outbox` |
| `OutboxPublishFailures` | `rate(beacon_outbox_publish_failures_total[5m]) > 0` by reason, for 5m | which side fails: `kafka`, `timeout`, `database`, `other` | api log line of the failure |
| `MaterializerConflicts` | `increase(beacon_materializer_conflict_total[5m]) > 0` | same offset, different `provenance_checksum` (or partition mismatch): parity investigation | `docs/runbooks/dlq.md` |
| `MaterializerErrors` | `increase(beacon_materializer_errors_total[5m]) > 0` | snapshot transaction or DLQ write failing; the record is redelivered without limit, so lag follows | api log, Postgres |
| `ConsumerLagHigh` | `max(kafka_consumer_fetch_manager_records_lag_max{job="beacon-api"}) > 1000` for 5m | api materializer behind | materializer errors panel, Postgres |
| `FlinkSourceLagHigh` | `max(flink_..._KafkaSourceReader_KafkaConsumer_records_lag_max) > 1000` for 5m | Flink job behind on `attempts.v1` | Flink UI backpressure, taskmanager log |
| `FlinkCheckpointStale` | `changes(flink_jobmanager_job_numberOfCompletedCheckpoints[2m]) == 0 and flink_jobmanager_job_uptime > 60000` | a running job (uptime > 60 s) completed no checkpoint in 2 min at a 10 s interval; the series only exists while a job is RUNNING, so a cancelled job does not fire it | `/checkpoints` volume ownership (flink-init), taskmanager log |
| `FlinkCheckpointFailures` | `increase(flink_jobmanager_job_numberOfFailedCheckpoints[5m]) > 0` | failed checkpoints | jobmanager log |
| `DlqRate` | `increase(invalid + conflict + dlq_published [5m]) > 0` | the api materializer sent records to `stream-dlq.v1` | `make dlq-status`, `docs/runbooks/dlq.md` |
| `FlinkDlqRate` | `increase(numRecordsIn{operator_name="sink_stream_dlq_v1:_Writer"}[5m]) > 0` | the Flink job wrote to `stream-dlq.v1` | same |
| `ApiErrorRate` | 5xx / all over 5m > 5 %, for 5m | stands in for the latency-p99 alert that needs histogram buckets (part 2) | api log, latency max panel |

Validate after editing: `docker compose ... exec prometheus promtool check rules
/etc/prometheus/alerts.yml`, then `docker compose ... restart prometheus` (the lifecycle API
is off; the file is a read-only bind mount, so a restart is the reload).

## 5. Memory budget

Measured with `docker stats --no-stream` during the proof (docs/evidence/BC-0308/docker-stats.txt),
full stack, after the synthetic traffic and with the Flink job running:

| Container | Limit | Measured | Note |
|---|---|---|---|
| taskmanager | 1.5 GiB | 895 MiB | 547 MiB without the agent (BC-0301 proof) |
| jobmanager | 768m in `flink.yml`, **896m under this profile** | 668 MiB (682 MiB = 89 % of 768m in the first run) | the agent's metaspace on top of Flink's 640m process size; hence the override |
| api | 1 GiB | 572 MiB | 424 MiB without the agent |
| kafka | 1 GiB | 406 MiB | |
| grafana | 512m | 339 MiB (`GOMEMLIMIT=384MiB`, three unused bundled apps disabled) | 510 MiB of 512 MiB before those two settings, not killed but at the cap |
| tempo | 512m | 239 MiB | grows with the live blocks; 48 h retention |
| prometheus | 512m | 62 MiB | 2 d retention, 5 s scrape of four targets |
| postgres | 512m | 42 MiB | |
| web | 128m | 9 MiB | |

Sum of limits: core 2.2 GB + stream 3.3 GB (jobmanager +128 MiB here) + observability 1.5 GB
= 7.1 GB against the 7.6 GB Docker VM; measured use about 3.2 GB. The full stack fit on the
reference laptop; no OOM kill (`docker compose ps -a` shows no exit 137). On a smaller VM run
`make dev && make observability-up` (api view only; the Flink targets are DOWN by design).

## 6. Verification (the proof, 2026-10-18)

All outputs are in `docs/evidence/BC-0308/`:

| File | What it shows |
|---|---|
| `metrics-presence.txt` | `bash test/observability/metrics-presence.sh` exit 0: every required name FOUND, four targets up |
| `prometheus-scrape.txt` | `curl -s http://127.0.0.1:8080/prometheus \| grep -E '^beacon_\|^http_server_requests_seconds_count\|^kafka_consumer_fetch_manager_records_lag' \| sort` |
| `targets.txt` | `/api/v1/targets` summary: `beacon-api`, `flink-jobmanager`, `flink-taskmanager`, `prometheus` all `up` |
| `prometheus-rules.txt` | `/api/v1/rules`: 11 rules, `health=ok` (`promtool check rules`: SUCCESS) |
| `flink-scrape.txt` | the Flink series the dashboard and alerts use, with their real label values (`job_name="beacon_climb_features"`, `operator_name="sink_stream_dlq_v1:_Writer"`) |
| `tempo-search.txt` | TraceQL search for `POST /v1/me/attempts` (five trace ids) and the tag search by service |
| `tempo-trace-post-attempts.txt` | the spans of one such trace: route, method, status 201 and the JDBC children |
| `dashboard.png` | Playwright screenshot of the provisioned dashboard after login (`test/observability/grafana-screenshot.ts`) |
| `docker-stats.txt` | `docker stats --no-stream` of the full stack |

Reproduce:

```sh
make stream-up && make observability-up && make stream-submit && make seed-demo
# register a synthetic user and log a few attempts (any client; the proof used curl)
make observability-check
set -a; . ./.env; set +a; pnpm --filter @beacon/web exec playwright test --config ../../test/observability/playwright.config.ts
make observability-down && make stream-down
```

## 7. Rollback and what part 2 still owes

Rollback is `make observability-down` then `make stream-up`/`make dev` (section 3.5); nothing
in this profile writes to Postgres or Kafka, and the api/Flink overrides are environment only.

Not done in part 1 (api code, out of scope here):

1. **`request_id` / `event_id` span attributes** on the api's server span, the outbox publish
   and the materializer consume, so a seeded failure is traceable from `X-Request-Id` to the
   failing span. Today the trace shows route and status only.
2. **Histogram buckets** for `http.server.requests` (`management.metrics.distribution.percentiles-histogram.http.server.requests: true`
   and SLO buckets) so the latency panels become p50/p95/p99 and `ApiLatencyP99` replaces
   `ApiErrorRate` as the latency gate.
3. **A server-side feature-freshness gauge** (attempt committed to STREAM snapshot visible);
   until then the freshness panel is the applied-vs-published proxy and the number comes from
   `scripts/loadgen`.
4. `beacon_dlq_published_total` and `beacon_materializer_invalid_total` are registered on
   first use; registering them at construction (like the outbox counters) would make them
   `FOUND` at 0 and let the DLQ alert drop its `or vector(0)` guards.

## 8. Troubleshooting

**`make observability-up` stops with `dependency failed to start: container compose-otel-agent-init-1 exited (1)`**
: the agent jar download failed. `docker compose ... logs otel-agent-init` shows either a curl
error (no route to `github.com`: proxy or offline; the init container needs outbound HTTPS once)
or `pinned sha256 ... differs from published ...` (the release assets changed under the pinned
version: do not override the pin; check the release page and update version and sha together
in `observability.yml`). Nothing else starts until this exits 0. Once the jar is in the
`otel-agent` volume the init container only re-verifies it and needs no network.

**`api` unhealthy after `observability-up`, log shows `Error opening zip file or JAR manifest missing : /otel/opentelemetry-javaagent.jar`**
: the volume is mounted but empty or unreadable (someone removed the jar, or a chmod went
wrong). `docker volume rm compose_otel-agent` and run `make observability-up` again.

**Prometheus target `flink-jobmanager` / `flink-taskmanager` DOWN** : with `make dev` only,
expected (no Flink containers; the names do not resolve). With `make stream-up`: the reporter
did not start. `docker compose ... logs jobmanager | grep -i "prometheus\|dynamic configuration"`
must show `Loading dynamic configuration property: metrics.reporter.prom.factory.class, ...` and
`... prom.port, 9249`; if the entrypoint complains about the arguments, the `command:` override
in `observability.yml` was changed from the two-token `-D` / `key=value` form that
`jobmanager.sh` expects (a second token other than `-D` is treated as the legacy `<host>`
positional and breaks the RPC address). The plugin itself ships in
`/opt/flink/plugins/metrics-prometheus` of `flink:2.2.1-java21`; a different image tag may need
`ENABLE_BUILT_IN_PLUGINS=flink-metrics-prometheus-<version>.jar` instead.

**Flink panels empty although both targets are up** : no job is running
(`flink_jobmanager_numRunningJobs` 0). `make observability-up` recreated the jobmanager;
`make stream-submit` again.

**Tempo shows no traces** : `curl -s http://127.0.0.1:3200/ready` must say `ready`. The api log
should contain `opentelemetry-javaagent - version: 2.32.0` right after `Picked up
JAVA_TOOL_OPTIONS`; an exporter failure looks like `Failed to export spans ... tempo:4318`
(Tempo down or not yet started: the exporter retries and drops after its queue fills; new
requests show up once Tempo is back). Tempo's own log prints
`error calling scheduler err="rpc error: code = NotFound desc = no jobs found"` every 15 s from
the in-process backend worker in single-binary mode; that is noise, not a failure.

**Grafana `Dashboard not found` at `/d/beacon-streaming`** : the provisioning mount is missing
or the JSON is invalid. `docker compose ... logs grafana | grep -i "provision\|dashboard"`
names the file and the error; `python3 -I -m json.tool infra/observability/grafana/dashboards/beacon-streaming.json`
checks the syntax. Provisioned dashboards cannot be saved from the UI by design.

**Grafana login rejected** : the password is `GRAFANA_ADMIN_PASSWORD` from `.env` at the time
the `grafana-data` volume was first created; changing `.env` later does not change the stored
admin password (Grafana only applies `GF_SECURITY_ADMIN_PASSWORD` on first start). Either put
the old value back or `docker volume rm compose_grafana-data` (nothing else is stored there:
dashboards and datasources are provisioned from files).

**`FlinkDlqRate` firing on a clean stack** : the rule must select
`operator_name="sink_stream_dlq_v1:_Writer"`; the `: _Committer` operator's `numRecordsIn`
grows by one per checkpoint and is not a DLQ count.

**Jobmanager OOM-killed (exit 137) under this profile** : the agent costs about 230 MiB on
top of Flink's 640m process size; the profile's `mem_limit: 896m` override must be in effect
(`docker inspect compose-jobmanager-1 --format '{{.HostConfig.Memory}}'` = 939524096).
