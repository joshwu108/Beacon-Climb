#!/usr/bin/env python3
"""BC-0311: SYNTHETIC event load generator and freshness measurement for the Beacon Climb api.

Registers N users named synthetic_<runid>_<i>, records consent against the LIVE consent
document, sets board/angle preferences, then posts attempts at a global rate spread over the
users and measures, per event, when a POST /v1/me/sessions/recommendations for that user
first reports feature_as_of >= occurred_at (visible_at). Output: events.jsonl (one row per
event, every row "synthetic": true), summary.json and summary.txt (percentiles: nearest-rank,
see report.py). Standard library only; run with `python3 -I`.

Guards (asserted in code, not just documented):
  * every username this process authenticates as must start with `synthetic_` and match the
    contract username pattern; any other username raises before an HTTP call is made;
  * only GET/POST/PUT are ever sent and never to /v1/admin*: no delete, erase or admin call;
  * tokens and the per-run password live in memory only and are never printed or written.

Rate limits of the api (services/api/src/main/resources/application.yaml) bound one client:
60 writes/min per user (attempts AND recommendation polls count), 30/min with burst 5 per
client address for register/session and 20 registrations per address per UTC day. A 429 is
honoured with its Retry-After and counted; a Retry-After above --max-retry-after aborts the
setup phase with a pointer to infra/compose/loadgen.yml (docs/benchmarks/README.md).
"""

from __future__ import annotations

import argparse
import http.client
import json
import random
import re
import secrets
import subprocess
import sys
import threading
import time
import urllib.parse
import uuid
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))
import report  # noqa: E402

SYNTHETIC_PREFIX = "synthetic_"
USERNAME_RE = re.compile(r"^[a-z0-9_]{3,32}$")
ALLOWED_METHODS = frozenset({"GET", "POST", "PUT"})
FORBIDDEN_PATH_PREFIXES = ("/v1/admin",)
API_ENV_ALLOWED_PREFIXES = ("BEACON_HTTP_", "BEACON_AUTH_", "BEACON_DECISIONS_", "BEACON_STREAM_ENABLED")
API_ENV_DENY_SUBSTRINGS = ("PASSWORD", "SECRET", "TOKEN", "KEY")
DEFAULT_BASE_URL = "http://127.0.0.1:8080"
DEFAULT_API_CONTAINER = "compose-api-1"
MAX_ROUTE_PAGES = 5
PROGRESS_EVERY_S = 5.0
ATTEMPT_RETRIES_ON_429 = 5
SETUP_RETRIES = 30
GOAL = "TECHNIQUE"
GRADE_BAND = "INTERMEDIATE"


class SyntheticGuardError(RuntimeError):
    """Raised before any request that would touch a non-synthetic user or a forbidden endpoint."""


def assert_synthetic_username(username: str) -> str:
    if not username.startswith(SYNTHETIC_PREFIX) or not USERNAME_RE.match(username):
        raise SyntheticGuardError(f"refusing to act as non-synthetic or invalid username {username!r}")
    return username


def assert_allowed_request(method: str, path: str) -> None:
    if method not in ALLOWED_METHODS:
        raise SyntheticGuardError(f"refusing HTTP method {method} (only {sorted(ALLOWED_METHODS)} are allowed)")
    if any(path.startswith(prefix) for prefix in FORBIDDEN_PATH_PREFIXES):
        raise SyntheticGuardError(f"refusing path {path} (admin endpoints are never called)")


def now_iso_ms() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def log(message: str) -> None:
    print(f"loadgen: {message}", file=sys.stderr, flush=True)


@dataclass
class Response:
    status: int
    headers: dict[str, str]
    body: Any
    elapsed_ms: float
    started_at: str
    finished_at: str

    def retry_after_s(self, default: float = 1.0) -> float:
        raw = self.headers.get("retry-after")
        try:
            return max(0.0, float(raw)) if raw is not None else default
        except ValueError:
            return default


class ApiClient:
    """One keep-alive http.client connection per thread; reconnects once on a dropped socket."""

    def __init__(self, base_url: str, timeout_s: float) -> None:
        parsed = urllib.parse.urlsplit(base_url)
        if parsed.scheme != "http" or not parsed.hostname:
            raise ValueError(f"--base-url must be an http://host:port URL, got {base_url!r}")
        self.host = parsed.hostname
        self.port = parsed.port or 80
        self.timeout_s = timeout_s
        self._local = threading.local()

    def _connection(self, fresh: bool = False) -> http.client.HTTPConnection:
        conn = getattr(self._local, "conn", None)
        if conn is None or fresh:
            if conn is not None:
                conn.close()
            conn = http.client.HTTPConnection(self.host, self.port, timeout=self.timeout_s)
            self._local.conn = conn
        return conn

    def request(
        self,
        method: str,
        path: str,
        body: Any = None,
        token: str | None = None,
        extra_headers: dict[str, str] | None = None,
    ) -> Response:
        assert_allowed_request(method, path)
        headers = {"Accept": "application/json"}
        payload = None
        if body is not None:
            payload = json.dumps(body).encode("utf-8")
            headers["Content-Type"] = "application/json"
        if token is not None:
            headers["Authorization"] = f"Bearer {token}"
        if extra_headers:
            headers.update(extra_headers)
        started_at = now_iso_ms()
        t0 = time.perf_counter()
        for attempt in (1, 2):
            conn = self._connection(fresh=attempt == 2)
            try:
                conn.request(method, path, body=payload, headers=headers)
                raw = conn.getresponse()
                data = raw.read()
                break
            except (http.client.HTTPException, OSError):
                if attempt == 2:
                    raise
        elapsed_ms = round((time.perf_counter() - t0) * 1000.0, 3)
        finished_at = now_iso_ms()
        parsed: Any = None
        if data:
            try:
                parsed = json.loads(data.decode("utf-8"))
            except ValueError:
                parsed = None
        return Response(raw.status, {k.lower(): v for k, v in raw.getheaders()}, parsed, elapsed_ms, started_at, finished_at)


@dataclass
class Event:
    index: int
    username: str
    route_variant_id: str
    idempotency_key: str
    sent: bool
    occurred_at: str | None = None
    sent_at: str | None = None
    committed_at: str | None = None
    committed_monotonic: float | None = None
    commit_latency_ms: float | None = None
    status: int | str | None = None
    error: str | None = None
    attempt_id: str | None = None
    received_at: str | None = None
    server_clock_offset_ms: float | None = None
    rate_limited_responses: int = 0
    request_count: int = 0
    visibility: str = report.NOT_COMMITTED
    visible_at: str | None = None
    freshness_ms: float | None = None
    visible_poll_latency_ms: float | None = None
    feature_as_of: str | None = None
    features_cutoff_w: str | None = None
    features_source: str | None = None
    poll_count: int = 0

    def to_row(self, run_id: str) -> dict[str, Any]:
        row = {"synthetic": True, "run_id": run_id}
        for key, value in self.__dict__.items():
            if key != "committed_monotonic":
                row[key] = value
        return row


@dataclass
class User:
    index: int
    username: str
    token: str = field(repr=False)
    in_flight: threading.Lock = field(default_factory=threading.Lock, repr=False)
    pending_lock: threading.Lock = field(default_factory=threading.Lock, repr=False)
    pending: list[Event] = field(default_factory=list, repr=False)

    def __post_init__(self) -> None:
        assert_synthetic_username(self.username)

    def has_pending(self) -> bool:
        with self.pending_lock:
            return bool(self.pending)


@dataclass
class RunState:
    attempted: int = 0
    committed: int = 0
    failed: int = 0
    visible: int = 0
    not_visible: int = 0
    attempt_429: int = 0
    poll_429: int = 0
    poll_errors: int = 0
    lock: threading.Lock = field(default_factory=threading.Lock, repr=False)


# --- setup ---------------------------------------------------------------------------------------


def write_with_backoff(api: ApiClient, args: argparse.Namespace, what: str, **request: Any) -> Response:
    """Retries 429 (Retry-After) and 503 (Argon2 gate) during setup; aborts on a long Retry-After."""
    for _ in range(SETUP_RETRIES):
        resp = api.request(**request)
        if resp.status == 429:
            wait = resp.retry_after_s()
            if wait > args.max_retry_after:
                raise SystemExit(
                    f"loadgen: {what}: 429 RATE_LIMITED with Retry-After {wait:.0f}s > --max-retry-after "
                    f"{args.max_retry_after:.0f}s; raise the api limits for a load run with "
                    "`make loadgen-stack` (infra/compose/loadgen.yml) or wait (docs/benchmarks/README.md)"
                )
            log(f"{what}: 429, waiting {wait:.1f}s (Retry-After)")
            time.sleep(wait)
            continue
        if resp.status == 503:
            wait = resp.retry_after_s(default=1.0)
            log(f"{what}: 503, waiting {wait:.1f}s")
            time.sleep(min(wait, args.max_retry_after))
            continue
        return resp
    raise SystemExit(f"loadgen: {what}: gave up after {SETUP_RETRIES} retries")


def authenticate(api: ApiClient, args: argparse.Namespace, username: str, password: str) -> str:
    assert_synthetic_username(username)
    credentials = {"username": username, "password": password}
    resp = write_with_backoff(api, args, f"register {username}", method="POST", path="/v1/auth/register", body=credentials)
    if resp.status == 409:
        resp = write_with_backoff(api, args, f"session {username}", method="POST", path="/v1/auth/session", body=credentials)
    if resp.status != 201 or not isinstance(resp.body, dict) or "token" not in resp.body:
        raise SystemExit(f"loadgen: register/session for {username} failed with HTTP {resp.status}: {resp.body}")
    return str(resp.body["token"])


def fetch_consent_document(api: ApiClient) -> dict[str, str]:
    resp = api.request("GET", "/v1/consent/current")
    if resp.status != 200 or not isinstance(resp.body, dict):
        raise SystemExit(f"loadgen: GET /v1/consent/current failed with HTTP {resp.status}")
    return {"version": str(resp.body["version"]), "text_sha256": str(resp.body["text_sha256"])}


def setup_users(api: ApiClient, args: argparse.Namespace, run_id: str, password: str) -> list[User]:
    consent = fetch_consent_document(api)
    log(f"consent document version {consent['version']} (text sha256 {consent['text_sha256'][:12]}...)")
    users: list[User] = []
    t0 = time.perf_counter()
    for i in range(args.users):
        username = assert_synthetic_username(f"{SYNTHETIC_PREFIX}{run_id}_{i}")
        token = authenticate(api, args, username, password)
        consent_body = {
            "consent_version": consent["version"],
            "scopes": {"service": True, "research": False},
            "text_sha256": consent["text_sha256"],
        }
        resp = write_with_backoff(api, args, f"consent {username}", method="POST", path="/v1/me/consent", body=consent_body, token=token)
        if resp.status != 204:
            raise SystemExit(f"loadgen: POST /v1/me/consent for {username} failed with HTTP {resp.status}: {resp.body}")
        prefs = {"board_id": args.board, "angle_deg": args.angle, "initial_grade_band": GRADE_BAND, "goals": [GOAL]}
        resp = write_with_backoff(api, args, f"preferences {username}", method="PUT", path="/v1/me/preferences", body=prefs, token=token)
        if resp.status != 200:
            raise SystemExit(f"loadgen: PUT /v1/me/preferences for {username} failed with HTTP {resp.status}: {resp.body}")
        users.append(User(index=i, username=username, token=token))
        if (i + 1) % 10 == 0 or i + 1 == args.users:
            log(f"set up {i + 1}/{args.users} users ({time.perf_counter() - t0:.1f}s)")
    return users


def fetch_route_variant_ids(api: ApiClient, args: argparse.Namespace, token: str) -> list[str]:
    ids: list[str] = []
    cursor: str | None = None
    for _ in range(MAX_ROUTE_PAGES):
        query = {"board_id": args.board, "angle_deg": str(args.angle), "limit": "100"}
        if cursor:
            query["cursor"] = cursor
        resp = api.request("GET", "/v1/routes?" + urllib.parse.urlencode(query), token=token)
        if resp.status != 200 or not isinstance(resp.body, dict):
            raise SystemExit(f"loadgen: GET /v1/routes failed with HTTP {resp.status}: {resp.body}")
        ids.extend(str(item["route_variant_id"]) for item in resp.body["items"])
        cursor = resp.body.get("next_cursor")
        if not cursor:
            break
    if not ids:
        raise SystemExit(f"loadgen: no routes for board {args.board} at {args.angle} deg; run `make seed-demo` first")
    return ids


# --- production ----------------------------------------------------------------------------------


def post_attempt(api: ApiClient, args: argparse.Namespace, user: User, event: Event, state: RunState) -> None:
    body: dict[str, Any] | None = None
    first_sent_monotonic: float | None = None
    with user.in_flight:  # one attempt in flight per user: occurred_at order == commit order
        for _ in range(ATTEMPT_RETRIES_ON_429 + 1):
            if body is None:
                event.occurred_at = now_iso_ms()
                body = {
                    "route_variant_id": event.route_variant_id,
                    "occurred_at": event.occurred_at,
                    "attempts_count": 1,
                    "sent": event.sent,
                }
            event.request_count += 1
            try:
                resp = api.request("POST", "/v1/me/attempts", body, user.token, {"Idempotency-Key": event.idempotency_key})
            except (http.client.HTTPException, OSError) as exc:
                event.status = "error"
                event.error = f"{type(exc).__name__}: {exc}"[:200]
                break
            if first_sent_monotonic is None:
                first_sent_monotonic = time.perf_counter() - resp.elapsed_ms / 1000.0
                event.sent_at = resp.started_at
            if resp.status in (200, 201):
                event.status = resp.status
                event.committed_at = resp.finished_at
                event.committed_monotonic = time.perf_counter()
                event.commit_latency_ms = round((event.committed_monotonic - first_sent_monotonic) * 1000.0, 3)
                if isinstance(resp.body, dict):
                    event.attempt_id = resp.body.get("attempt_id")
                    event.received_at = resp.body.get("received_at")
                    if event.received_at:
                        event.server_clock_offset_ms = report.millis_between(event.committed_at, event.received_at)
                event.visibility = report.NOT_VISIBLE
                with user.pending_lock:
                    user.pending.append(event)
                break
            if resp.status == 429:
                event.rate_limited_responses += 1
                with state.lock:
                    state.attempt_429 += 1
                event.status = 429
                wait = min(resp.retry_after_s(), args.max_retry_after)
                if event.request_count > ATTEMPT_RETRIES_ON_429:
                    break
                time.sleep(wait)
                continue
            event.status = resp.status
            if isinstance(resp.body, dict):
                event.error = str(resp.body.get("code", ""))[:80]
            break
    with state.lock:
        if event.status in (200, 201):
            state.committed += 1
        else:
            state.failed += 1


def produce(api: ApiClient, args: argparse.Namespace, users: list[User], routes: list[str], state: RunState, stop: threading.Event) -> list[Event]:
    rng = random.Random(args.seed)
    total = int(round(args.rate * args.duration))
    interval = 1.0 / args.rate
    workers = max(4, min(32, int(args.rate / 5) + 1))
    events: list[Event] = []
    log(f"producing {total} events at {args.rate} events/s over {len(users)} users ({workers} worker threads)")
    with ThreadPoolExecutor(max_workers=workers, thread_name_prefix="producer") as pool:
        t0 = time.perf_counter()
        for k in range(total):
            due = t0 + k * interval
            while not stop.is_set():
                remaining = due - time.perf_counter()
                if remaining <= 0:
                    break
                time.sleep(min(remaining, 0.05))
            if stop.is_set():
                break
            user = users[k % len(users)]
            event = Event(
                index=k,
                username=user.username,
                route_variant_id=rng.choice(routes),
                idempotency_key=str(uuid.uuid4()),
                sent=rng.random() < 0.5,
            )
            events.append(event)
            with state.lock:
                state.attempted += 1
            pool.submit(post_attempt, api, args, user, event, state)
    return events


# --- visibility polling ----------------------------------------------------------------------------


def poll_user(api: ApiClient, args: argparse.Namespace, user: User, state: RunState) -> None:
    now_monotonic = time.perf_counter()
    with user.pending_lock:
        pending = list(user.pending)
    if not pending:
        return
    body = {
        "board_id": args.board,
        "angle_deg": args.angle,
        "goal": GOAL,
        "max_routes": 1,
        "session_id": str(uuid.uuid4()),
        "exclude_route_variant_ids": [],
    }
    try:
        resp = api.request("POST", "/v1/me/sessions/recommendations", body, user.token)
    except (http.client.HTTPException, OSError):
        with state.lock:
            state.poll_errors += 1
        resp = None
    feature_as_of = None
    if resp is not None and resp.status == 200 and isinstance(resp.body, dict):
        feature_as_of = report.parse_rfc3339(str(resp.body["feature_as_of"]))
    elif resp is not None and resp.status == 429:
        with state.lock:
            state.poll_429 += 1
        time.sleep(min(resp.retry_after_s(), args.max_retry_after))
    elif resp is not None:
        with state.lock:
            state.poll_errors += 1
    newly_visible = 0
    expired = 0
    with user.pending_lock:
        keep: list[Event] = []
        for event in user.pending:
            event.poll_count += 1
            if feature_as_of is not None and resp is not None and event.occurred_at and report.parse_rfc3339(event.occurred_at) <= feature_as_of:
                event.visibility = report.VISIBLE
                event.visible_at = resp.finished_at
                event.freshness_ms = report.millis_between(event.occurred_at, event.visible_at)
                event.visible_poll_latency_ms = resp.elapsed_ms
                event.feature_as_of = str(resp.body["feature_as_of"])
                event.features_cutoff_w = resp.body.get("features_cutoff_w")
                event.features_source = resp.body.get("features_source")
                newly_visible += 1
            elif event.committed_monotonic is not None and now_monotonic - event.committed_monotonic > args.visible_timeout:
                event.visibility = report.NOT_VISIBLE
                expired += 1
            else:
                keep.append(event)
        user.pending = keep
    with state.lock:
        state.visible += newly_visible
        state.not_visible += expired


def poll_until_drained(api: ApiClient, args: argparse.Namespace, users: list[User], state: RunState, producing_done: threading.Event, stop: threading.Event) -> None:
    with ThreadPoolExecutor(max_workers=min(32, len(users)), thread_name_prefix="poller") as pool:
        while not stop.is_set():
            round_start = time.perf_counter()
            active = [u for u in users if u.has_pending()]
            if not active and producing_done.is_set():
                return
            list(pool.map(lambda u: poll_user(api, args, u, state), active))
            time.sleep(max(0.0, args.poll_interval - (time.perf_counter() - round_start)))


def progress_reporter(state: RunState, stop: threading.Event, started: float) -> None:
    while not stop.wait(PROGRESS_EVERY_S):
        with state.lock:
            log(
                f"t={time.perf_counter() - started:6.1f}s attempted={state.attempted} committed={state.committed} "
                f"failed={state.failed} visible={state.visible} not_visible={state.not_visible} "
                f"429s attempts={state.attempt_429} polls={state.poll_429} poll_errors={state.poll_errors}"
            )


# --- environment description ---------------------------------------------------------------------


def _run(cmd: list[str]) -> str | None:
    try:
        out = subprocess.run(cmd, capture_output=True, text=True, timeout=15, check=False)
    except (OSError, subprocess.TimeoutExpired):
        return None
    return out.stdout.strip() if out.returncode == 0 else None


def hardware_block() -> dict[str, Any]:
    mem = _run(["sysctl", "-n", "hw.memsize"])
    block: dict[str, Any] = {
        "cpu": _run(["sysctl", "-n", "machdep.cpu.brand_string"]) or "unavailable",
        "memory_gib": round(int(mem) / 2**30, 1) if mem and mem.isdigit() else "unavailable",
        "os": " ".join(filter(None, [_run(["sw_vers", "-productName"]), _run(["sw_vers", "-productVersion"]), _run(["sw_vers", "-buildVersion"])])) or "unavailable",
        "docker": _run(["docker", "info", "--format", "engine {{.ServerVersion}}, {{.NCPU}} cpus, {{.MemTotal}} bytes for containers"]) or "unavailable",
        "python": sys.version.split()[0],
        "client": "this script on the same laptop as the compose stack (loopback); client clock for every timestamp",
    }
    return block


def api_environment(container: str) -> dict[str, str]:
    """Allow-listed BEACON_* variables of the api container (never anything secret-looking)."""
    if not container:
        return {}
    raw = _run(["docker", "inspect", "--format", "{{json .Config.Env}}", container])
    if not raw:
        return {"note": f"container {container} not inspected (docker inspect failed); defaults from application.yaml assumed"}
    env: dict[str, str] = {}
    for item in json.loads(raw):
        name, _, value = item.partition("=")
        if name.startswith(API_ENV_ALLOWED_PREFIXES) and not any(d in name for d in API_ENV_DENY_SUBSTRINGS):
            env[name] = value
    env.setdefault("note", "unlisted beacon.http/auth/decisions values are the application.yaml defaults")
    return env


# --- main ----------------------------------------------------------------------------------------


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL)
    parser.add_argument("--users", type=int, default=10, help="synthetic users to register (default 10)")
    parser.add_argument("--rate", type=float, default=5.0, help="global attempts per second (default 5)")
    parser.add_argument("--duration", type=float, default=30.0, help="production window in seconds (default 30)")
    parser.add_argument("--board", default="synthetic-demo-1")
    parser.add_argument("--angle", type=int, default=40)
    parser.add_argument("--out", default=None, help="output dir (default reports/loadgen/<UTC timestamp>, gitignored)")
    parser.add_argument("--evidence-copy", default=None, help="also write summary.txt to this path")
    parser.add_argument("--seed", type=int, default=42, help="seed for route choice and sent flag")
    parser.add_argument("--poll-interval", type=float, default=1.0, help="seconds between recommendation polls per user with pending events")
    parser.add_argument("--visible-timeout", type=float, default=120.0, help="seconds after commit before an event counts as NOT_VISIBLE")
    parser.add_argument("--max-retry-after", type=float, default=60.0, help="longest Retry-After honoured before aborting setup / giving up")
    parser.add_argument("--timeout", type=float, default=10.0, help="per-request socket timeout in seconds")
    parser.add_argument("--api-container", default=DEFAULT_API_CONTAINER, help="docker container to inspect for BEACON_* overrides ('' to skip)")
    parser.add_argument("--self-test", action="store_true", help="run the percentile/summary/guard self-test and exit")
    args = parser.parse_args(argv)
    if not args.self_test:
        if args.users < 1 or args.rate <= 0 or args.duration <= 0:
            parser.error("--users >= 1, --rate > 0 and --duration > 0 are required")
        if args.poll_interval <= 0 or args.visible_timeout <= 0:
            parser.error("--poll-interval and --visible-timeout must be positive")
    return args


def self_test() -> int:
    report.self_test()
    for bad in ("demo", "admin", "synthetic-x", "Synthetic_a", "e2e_abc", "synthetic_a b", "synth_abc"):
        try:
            assert_synthetic_username(bad)
        except SyntheticGuardError:
            continue
        raise AssertionError(f"guard accepted {bad!r}")
    assert_synthetic_username("synthetic_abc_0")
    for method, path in (("DELETE", "/v1/me"), ("PATCH", "/v1/me/attempts/x"), ("GET", "/v1/admin/experiments")):
        try:
            assert_allowed_request(method, path)
        except SyntheticGuardError:
            continue
        raise AssertionError(f"guard accepted {method} {path}")
    assert_allowed_request("POST", "/v1/me/attempts")
    print("OK")
    return 0


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    if args.self_test:
        return self_test()
    started_wall = now_iso_ms()
    run_id = f"{int(time.time()):x}{secrets.token_hex(2)}"  # lowercase hex, unique per run
    password = secrets.token_urlsafe(24)  # memory only, never printed or written
    out_dir = Path(args.out) if args.out else Path("reports") / "loadgen" / started_wall.replace(":", "").replace("-", "").replace(".", "")
    evidence_copy = Path(args.evidence_copy) if args.evidence_copy else None
    api = ApiClient(args.base_url, args.timeout)
    log(f"run {run_id}: {args.users} users, {args.rate} events/s for {args.duration}s against {args.base_url}; out {out_dir}")
    ready = api.request("GET", "/health/ready")
    if ready.status != 200:
        raise SystemExit(f"loadgen: {args.base_url}/health/ready returned HTTP {ready.status}; run `make dev && make seed-demo` first")

    users = setup_users(api, args, run_id, password)
    routes = fetch_route_variant_ids(api, args, users[0].token)
    log(f"{len(routes)} route variants on {args.board} at {args.angle} deg")

    state = RunState()
    stop = threading.Event()
    producing_done = threading.Event()
    started = time.perf_counter()
    reporter = threading.Thread(target=progress_reporter, args=(state, stop, started), daemon=True)
    poller = threading.Thread(target=poll_until_drained, args=(api, args, users, state, producing_done, stop), daemon=True)
    reporter.start()
    poller.start()
    interrupted = False
    events: list[Event] = []
    try:
        events = produce(api, args, users, routes, state, stop)
        producing_done.set()
        log(f"production finished: attempted={state.attempted} committed={state.committed} failed={state.failed}; polling until every event is visible or times out ({args.visible_timeout:.0f}s)")
        poller.join()
    except KeyboardInterrupt:
        interrupted = True
        log("interrupted; writing what was recorded")
        stop.set()
        producing_done.set()
    finally:
        stop.set()
    for event in events:
        if event.status in (200, 201) and event.visibility == report.NOT_COMMITTED:
            event.visibility = report.NOT_VISIBLE
    # events the poller never got to flag (interrupt) stay NOT_VISIBLE; failed ones stay NOT_COMMITTED

    out_dir.mkdir(parents=True, exist_ok=True)
    rows = [event.to_row(run_id) for event in events]
    with (out_dir / "events.jsonl").open("w", encoding="utf-8") as handle:
        for row in rows:
            handle.write(json.dumps(row, sort_keys=False) + "\n")
    meta = {
        "run_id": run_id,
        "generated_at": now_iso_ms(),
        "parameters": {
            "base_url": args.base_url,
            "users": args.users,
            "rate": args.rate,
            "duration_s": args.duration,
            "board": args.board,
            "angle_deg": args.angle,
            "seed": args.seed,
            "poll_interval_s": args.poll_interval,
            "visible_timeout_s": args.visible_timeout,
            "started_at": started_wall,
            "username_prefix": f"{SYNTHETIC_PREFIX}{run_id}_",
            "events_file": str(out_dir / "events.jsonl"),
        },
        "hardware": hardware_block(),
        "api_environment": api_environment(args.api_container),
        "poll_rate_limited_responses": state.poll_429,
        "interrupted": interrupted,
    }
    summary = report.summarise(rows, meta)
    summary["poll_errors_non_200_non_429"] = state.poll_errors
    report.write_outputs(summary, out_dir, evidence_copy)
    print(report.render_text(summary))
    log(f"wrote {out_dir / 'events.jsonl'}, summary.json and summary.txt" + (f"; evidence copy {evidence_copy}" if evidence_copy else ""))
    counts = summary["counts"]
    return 1 if interrupted or counts["failed"] or counts["not_visible"] else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
