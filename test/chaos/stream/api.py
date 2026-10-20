#!/usr/bin/env python3
"""BC-0307: the api client of the chaos harness (standard library only; run with `python3 -I`).

One sub-command per canonical write or read the scenarios need, so that every scenario script
drives the SAME request path as a real client (scripts/loadgen/loadgen.py does the same for the
load generator; this file is deliberately separate so the chaos suite does not import it):

  setup      register a SYNTHETIC user, record consent against the live consent document, set
             board/angle preferences; the bearer token goes to --token-file (0600), never stdout
  attempt    POST /v1/me/attempts with an Idempotency-Key, retrying with the SAME key on a
             connection error / 503 until --deadline (the api may be SIGKILLed mid-request)
  patch      PATCH /v1/me/attempts/{id} with If-Match (correction or retraction)
  recommend  POST /v1/me/sessions/recommendations; prints the freshness members only
  burst      N sequential POSTs with fresh keys, each retried as `attempt` does; prints a
             summary and writes one JSON line per key to --out

Guards (asserted in code, not just documented):
  * the base URL host must be 127.0.0.1 (never a LAN or remote host);
  * every username this process registers or acts as must start with `synthetic_` and match
    the contract username pattern; anything else raises before an HTTP call is made;
  * only GET/POST/PUT/PATCH are ever sent and never to /v1/admin*: no DELETE, no erase, no admin;
  * tokens and the per-process password live in memory and the 0600 token file only; they are
    never printed. Output is JSON on stdout, progress on stderr.
"""

from __future__ import annotations

import argparse
import http.client
import json
import os
import re
import secrets
import sys
import time
import urllib.parse
import uuid
from datetime import datetime, timezone
from typing import Any

SYNTHETIC_PREFIX = "synthetic_"
USERNAME_RE = re.compile(r"^[a-z0-9_]{3,32}$")
ALLOWED_METHODS = frozenset({"GET", "POST", "PUT", "PATCH"})
FORBIDDEN_PATH_PREFIXES = ("/v1/admin",)
ALLOWED_HOSTS = frozenset({"127.0.0.1"})
DEFAULT_BASE_URL = "http://127.0.0.1:8080"
RETRY_INTERVAL_S = 0.5
GOAL = "TECHNIQUE"
GRADE_BAND = "INTERMEDIATE"
SETUP_RETRIES = 60


class GuardError(RuntimeError):
    """Raised before any request that would touch a non-synthetic user, a forbidden endpoint or host."""


def assert_synthetic_username(username: str) -> str:
    if not username.startswith(SYNTHETIC_PREFIX) or not USERNAME_RE.match(username):
        raise GuardError(f"refusing to act as non-synthetic or invalid username {username!r}")
    return username


def assert_allowed_request(method: str, path: str) -> None:
    if method not in ALLOWED_METHODS:
        raise GuardError(f"refusing HTTP method {method} (only {sorted(ALLOWED_METHODS)} are allowed)")
    if any(path.startswith(prefix) for prefix in FORBIDDEN_PATH_PREFIXES):
        raise GuardError(f"refusing path {path} (admin endpoints are never called)")


def now_iso_ms() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def log(message: str) -> None:
    print(f"chaos-api: {message}", file=sys.stderr, flush=True)


class Response:
    def __init__(self, status: int, headers: dict[str, str], body: Any) -> None:
        self.status = status
        self.headers = headers
        self.body = body

    def retry_after_s(self, default: float = 1.0) -> float:
        raw = self.headers.get("retry-after")
        try:
            return max(0.0, float(raw)) if raw is not None else default
        except ValueError:
            return default


class ApiClient:
    """One http.client connection, reconnected on every request that follows a dropped socket."""

    def __init__(self, base_url: str, timeout_s: float) -> None:
        parsed = urllib.parse.urlsplit(base_url)
        if parsed.scheme != "http" or not parsed.hostname:
            raise GuardError(f"--base-url must be an http://host:port URL, got {base_url!r}")
        if parsed.hostname not in ALLOWED_HOSTS:
            raise GuardError(f"refusing base URL host {parsed.hostname!r}: the chaos suite only ever runs against 127.0.0.1")
        self.host = parsed.hostname
        self.port = parsed.port or 80
        self.timeout_s = timeout_s
        self.conn: http.client.HTTPConnection | None = None

    def request(self, method: str, path: str, body: Any = None, token: str | None = None, extra: dict[str, str] | None = None) -> Response:
        """One request. Raises OSError / http.client.HTTPException when the api is unreachable."""
        assert_allowed_request(method, path)
        headers = {"Accept": "application/json"}
        payload = None
        if body is not None:
            payload = json.dumps(body).encode("utf-8")
            headers["Content-Type"] = "application/json"
        if token is not None:
            headers["Authorization"] = f"Bearer {token}"
        if extra:
            headers.update(extra)
        if self.conn is None:
            self.conn = http.client.HTTPConnection(self.host, self.port, timeout=self.timeout_s)
        try:
            self.conn.request(method, path, body=payload, headers=headers)
            raw = self.conn.getresponse()
            data = raw.read()
        except (http.client.HTTPException, OSError):
            self.conn.close()
            self.conn = None
            raise
        parsed: Any = None
        if data:
            try:
                parsed = json.loads(data.decode("utf-8"))
            except ValueError:
                parsed = None
        return Response(raw.status, {k.lower(): v for k, v in raw.getheaders()}, parsed)


def read_token(path: str) -> str:
    with open(path, encoding="utf-8") as handle:
        token = handle.read().strip()
    if not token:
        raise GuardError(f"token file {path} is empty")
    return token


def write_token(path: str, token: str) -> None:
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as handle:
        handle.write(token)


def emit(obj: dict[str, Any]) -> None:
    print(json.dumps(obj, sort_keys=True), flush=True)


# --- setup ---------------------------------------------------------------------------------------


def write_with_backoff(api: ApiClient, what: str, max_retry_after: float, **request: Any) -> Response:
    """Retries 429 (Retry-After) and 503 (Argon2 gate) during setup; a long Retry-After aborts."""
    for _ in range(SETUP_RETRIES):
        resp = api.request(**request)
        if resp.status == 429:
            wait = resp.retry_after_s()
            if wait > max_retry_after:
                raise SystemExit(
                    f"chaos-api: {what}: 429 RATE_LIMITED with Retry-After {wait:.0f}s > {max_retry_after:.0f}s; "
                    "the per-IP daily registration cap (beacon.auth.register-per-ip-per-day, default 20) is the "
                    "usual cause: `make reset-db` for a fresh database or wait for midnight UTC"
                )
            log(f"{what}: 429, waiting {wait:.1f}s (Retry-After)")
            time.sleep(wait)
            continue
        if resp.status == 503:
            wait = resp.retry_after_s(default=1.0)
            log(f"{what}: 503, waiting {wait:.1f}s")
            time.sleep(min(wait, max_retry_after))
            continue
        return resp
    raise SystemExit(f"chaos-api: {what}: gave up after {SETUP_RETRIES} retries")


def cmd_setup(api: ApiClient, args: argparse.Namespace) -> int:
    username = assert_synthetic_username(args.username)
    password = secrets.token_urlsafe(24)  # memory only; the user is never logged in again
    credentials = {"username": username, "password": password}
    resp = write_with_backoff(api, f"register {username}", args.max_retry_after, method="POST", path="/v1/auth/register", body=credentials)
    if resp.status != 201 or not isinstance(resp.body, dict) or "token" not in resp.body:
        raise SystemExit(f"chaos-api: register {username} failed with HTTP {resp.status}: {resp.body}")
    token = str(resp.body["token"])
    write_token(args.token_file, token)
    consent = api.request("GET", "/v1/consent/current")
    if consent.status != 200 or not isinstance(consent.body, dict):
        raise SystemExit(f"chaos-api: GET /v1/consent/current failed with HTTP {consent.status}")
    consent_body = {
        "consent_version": str(consent.body["version"]),
        "scopes": {"service": True, "research": False},
        "text_sha256": str(consent.body["text_sha256"]),
    }
    resp = write_with_backoff(api, f"consent {username}", args.max_retry_after, method="POST", path="/v1/me/consent", body=consent_body, token=token)
    if resp.status != 204:
        raise SystemExit(f"chaos-api: POST /v1/me/consent failed with HTTP {resp.status}: {resp.body}")
    prefs = {"board_id": args.board, "angle_deg": args.angle, "initial_grade_band": GRADE_BAND, "goals": [GOAL]}
    resp = write_with_backoff(api, f"preferences {username}", args.max_retry_after, method="PUT", path="/v1/me/preferences", body=prefs, token=token)
    if resp.status != 200:
        raise SystemExit(f"chaos-api: PUT /v1/me/preferences failed with HTTP {resp.status}: {resp.body}")
    query = urllib.parse.urlencode({"board_id": args.board, "angle_deg": str(args.angle), "limit": "100"})
    routes = api.request("GET", "/v1/routes?" + query, token=token)
    if routes.status != 200 or not isinstance(routes.body, dict):
        raise SystemExit(f"chaos-api: GET /v1/routes failed with HTTP {routes.status}: {routes.body}")
    ids = [str(item["route_variant_id"]) for item in routes.body["items"]]
    if not ids:
        raise SystemExit(f"chaos-api: no routes for board {args.board} at {args.angle} deg; run `make seed-demo` first")
    emit({"username": username, "consent_version": consent_body["consent_version"], "routes": ids[: args.max_routes], "synthetic": True})
    return 0


# --- attempts ------------------------------------------------------------------------------------


def post_with_same_key(api: ApiClient, token: str, body: dict[str, Any], key: str, deadline_s: float) -> dict[str, Any]:
    """POST /v1/me/attempts with one Idempotency-Key until a definite answer or the deadline.

    Retried: connection errors (api down), 503, 429 (Retry-After) and 5xx. Not retried: any other
    status. The same key makes every retry a replay of the same canonical write (ADR-0002).
    """
    started = time.monotonic()
    requests = 0
    connection_errors = 0
    first_error_at: str | None = None
    last_error_at: str | None = None
    while True:
        requests += 1
        try:
            resp = api.request("POST", "/v1/me/attempts", body, token, {"Idempotency-Key": key})
        except (http.client.HTTPException, OSError) as exc:
            connection_errors += 1
            last_error_at = now_iso_ms()
            first_error_at = first_error_at or last_error_at
            if time.monotonic() - started > deadline_s:
                return {"key": key, "status": "unreachable", "error": f"{type(exc).__name__}", "requests": requests, "connection_errors": connection_errors, "first_error_at": first_error_at, "last_error_at": last_error_at}
            time.sleep(RETRY_INTERVAL_S)
            continue
        if resp.status in (200, 201):
            attempt = resp.body if isinstance(resp.body, dict) else {}
            return {
                "key": key,
                "status": resp.status,
                "attempt_id": attempt.get("attempt_id"),
                "revision": attempt.get("revision"),
                "attempt_status": attempt.get("status"),
                "occurred_at": attempt.get("occurred_at"),
                "received_at": attempt.get("received_at"),
                "requests": requests,
                "connection_errors": connection_errors,
                "first_error_at": first_error_at,
                "last_error_at": last_error_at,
            }
        if resp.status == 429 or resp.status == 503 or resp.status >= 500:
            last_error_at = now_iso_ms()
            first_error_at = first_error_at or last_error_at
            if time.monotonic() - started > deadline_s:
                return {"key": key, "status": resp.status, "error": str((resp.body or {}).get("code", "")) if isinstance(resp.body, dict) else "", "requests": requests, "connection_errors": connection_errors, "first_error_at": first_error_at, "last_error_at": last_error_at}
            time.sleep(min(resp.retry_after_s(default=RETRY_INTERVAL_S), 5.0))
            continue
        return {"key": key, "status": resp.status, "error": str(resp.body.get("code", "")) if isinstance(resp.body, dict) else "", "requests": requests, "connection_errors": connection_errors, "first_error_at": first_error_at, "last_error_at": last_error_at}


def attempt_body(route_variant_id: str, occurred_at: str, sent: bool) -> dict[str, Any]:
    return {"route_variant_id": route_variant_id, "occurred_at": occurred_at, "attempts_count": 1, "sent": sent}


def cmd_attempt(api: ApiClient, args: argparse.Namespace) -> int:
    token = read_token(args.token_file)
    occurred_at = args.occurred_at if args.occurred_at != "now" else now_iso_ms()
    key = args.idempotency_key or str(uuid.uuid4())
    result = post_with_same_key(api, token, attempt_body(args.route_variant_id, occurred_at, args.sent == "true"), key, args.deadline)
    result["requested_occurred_at"] = occurred_at
    emit(result)
    return 0 if result["status"] in (200, 201) else 1


def cmd_patch(api: ApiClient, args: argparse.Namespace) -> int:
    token = read_token(args.token_file)
    body = json.loads(args.body)
    if not isinstance(body, dict):
        raise SystemExit("chaos-api: --body must be a JSON object")
    resp = api.request("PATCH", f"/v1/me/attempts/{args.attempt_id}", body, token, {"If-Match": f'"{args.if_match}"'})
    attempt = resp.body if isinstance(resp.body, dict) else {}
    emit({"status": resp.status, "attempt_id": attempt.get("attempt_id"), "revision": attempt.get("revision"), "attempt_status": attempt.get("status"), "sent": attempt.get("sent"), "occurred_at": attempt.get("occurred_at"), "error": attempt.get("code") if resp.status >= 400 else None})
    return 0 if resp.status == 200 else 1


def cmd_recommend(api: ApiClient, args: argparse.Namespace) -> int:
    token = read_token(args.token_file)
    body = {"board_id": args.board, "angle_deg": args.angle, "goal": GOAL, "max_routes": 1, "session_id": str(uuid.uuid4()), "exclude_route_variant_ids": []}
    resp = api.request("POST", "/v1/me/sessions/recommendations", body, token)
    rec = resp.body if isinstance(resp.body, dict) else {}
    emit({"status": resp.status, "feature_as_of": rec.get("feature_as_of"), "features_source": rec.get("features_source"), "features_cutoff_w": rec.get("features_cutoff_w"), "decision_id": rec.get("decision_id"), "error": rec.get("code") if resp.status >= 400 else None})
    return 0 if resp.status == 200 else 1


def cmd_burst(api: ApiClient, args: argparse.Namespace) -> int:
    token = read_token(args.token_file)
    routes = [r for r in args.route_variant_ids.split(",") if r]
    if not routes:
        raise SystemExit("chaos-api: --route-variant-ids must name at least one route")
    results: list[dict[str, Any]] = []
    started = now_iso_ms()
    with open(args.out, "w", encoding="utf-8") as handle:
        for i in range(args.count):
            key = str(uuid.uuid4())
            body = attempt_body(routes[i % len(routes)], now_iso_ms(), i % 2 == 0)
            result = post_with_same_key(api, token, body, key, args.deadline)
            result["index"] = i
            results.append(result)
            handle.write(json.dumps(result, sort_keys=True) + "\n")
            handle.flush()
            if result["status"] not in (200, 201):
                log(f"burst: key {i} ended with {result['status']} after {result['requests']} request(s)")
    ok_201 = sum(1 for r in results if r["status"] == 201)
    ok_200 = sum(1 for r in results if r["status"] == 200)
    retried = sum(1 for r in results if r["requests"] > 1)
    summary = {
        "count": args.count,
        "distinct_keys": len({r["key"] for r in results}),
        "ok_201": ok_201,
        "ok_200_replay": ok_200,
        "failed": args.count - ok_201 - ok_200,
        "keys_retried": retried,
        "connection_errors": sum(r["connection_errors"] for r in results),
        "first_error_at": min((r["first_error_at"] for r in results if r["first_error_at"]), default=None),
        "last_error_at": max((r["last_error_at"] for r in results if r["last_error_at"]), default=None),
        "attempt_ids": [r.get("attempt_id") for r in results if r.get("attempt_id")],
        "started_at": started,
        "finished_at": now_iso_ms(),
        "out": args.out,
    }
    emit(summary)
    return 0 if summary["failed"] == 0 else 1


# --- main ----------------------------------------------------------------------------------------


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL)
    parser.add_argument("--timeout", type=float, default=10.0, help="per-request socket timeout in seconds")
    sub = parser.add_subparsers(dest="command", required=True)

    p = sub.add_parser("setup")
    p.add_argument("--username", required=True)
    p.add_argument("--token-file", required=True)
    p.add_argument("--board", default="synthetic-demo-1")
    p.add_argument("--angle", type=int, default=40)
    p.add_argument("--max-routes", type=int, default=20)
    p.add_argument("--max-retry-after", type=float, default=60.0)

    p = sub.add_parser("attempt")
    p.add_argument("--token-file", required=True)
    p.add_argument("--route-variant-id", required=True)
    p.add_argument("--occurred-at", default="now", help="RFC 3339 instant or 'now' (client clock, ms)")
    p.add_argument("--sent", choices=("true", "false"), default="true")
    p.add_argument("--idempotency-key", default=None)
    p.add_argument("--deadline", type=float, default=120.0, help="seconds to keep retrying with the same key")

    p = sub.add_parser("patch")
    p.add_argument("--token-file", required=True)
    p.add_argument("--attempt-id", required=True)
    p.add_argument("--if-match", required=True, help="current revision number")
    p.add_argument("--body", required=True, help='JSON object, e.g. {"sent": false} or {"retract": true}')

    p = sub.add_parser("recommend")
    p.add_argument("--token-file", required=True)
    p.add_argument("--board", default="synthetic-demo-1")
    p.add_argument("--angle", type=int, default=40)

    p = sub.add_parser("burst")
    p.add_argument("--token-file", required=True)
    p.add_argument("--count", type=int, required=True)
    p.add_argument("--route-variant-ids", required=True, help="comma-separated")
    p.add_argument("--deadline", type=float, default=120.0)
    p.add_argument("--out", required=True, help="JSON lines, one per key")

    p = sub.add_parser("self-test")
    return parser.parse_args(argv)


def self_test() -> int:
    for bad in ("demo", "admin", "synthetic-x", "Synthetic_a", "e2e_abc", "synthetic_a b", "synth_abc"):
        try:
            assert_synthetic_username(bad)
        except GuardError:
            continue
        raise AssertionError(f"guard accepted {bad!r}")
    assert_synthetic_username("synthetic_chaos_dup_0a1b2c")
    for method, path in (("DELETE", "/v1/me"), ("POST", "/v1/admin/experiments"), ("GET", "/v1/admin/experiments")):
        try:
            assert_allowed_request(method, path)
        except GuardError:
            continue
        raise AssertionError(f"guard accepted {method} {path}")
    assert_allowed_request("PATCH", "/v1/me/attempts/x")
    for url in ("http://localhost:8080", "http://192.168.1.2:8080", "http://api.example.test"):
        try:
            ApiClient(url, 1.0)
        except GuardError:
            continue
        raise AssertionError(f"guard accepted base url {url}")
    ApiClient("http://127.0.0.1:8080", 1.0)
    print("OK")
    return 0


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    if args.command == "self-test":
        return self_test()
    api = ApiClient(args.base_url, args.timeout)
    handlers = {"setup": cmd_setup, "attempt": cmd_attempt, "patch": cmd_patch, "recommend": cmd_recommend, "burst": cmd_burst}
    return handlers[args.command](api, args)


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1:]))
    except GuardError as exc:
        print(f"chaos-api: GUARD: {exc}", file=sys.stderr)
        sys.exit(3)
