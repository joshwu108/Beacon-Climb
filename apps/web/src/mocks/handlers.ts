/**
 * One MSW handler per implemented (non x-planned) operation in contracts/openapi.yaml
 * 1.1.0. Every response body, status and header comes from contracts/examples, imported
 * directly so the fixtures are never copied. Operations marked x-planned (deleteMe,
 * replayDecision, compareDecision, getSkillMemory, getExport, listExperiments,
 * rollbackExperiment) have no handler and fall through to the network (and 501 upstream).
 *
 * Paths are matched on any origin ("*" prefix) so the same handlers serve the browser
 * worker (VITE_API_BASE_URL) and the Node server used by tests.
 *
 * Non-fixture responses (documented in README "Mocks"): 401 UNAUTHENTICATED, 404
 * NOT_FOUND, 403 CONSENT_REQUIRED, the derived 422s (UNSUPPORTED_ANGLE, UNKNOWN_BOARD,
 * INVALID_CURSOR, VALIDATION_FAILED), the generated catalog pages served by listRoutes /
 * getRoute (src/mocks/catalog.ts), the echoed Preferences body from putPreferences, the
 * per-process GET /v1/me profile, the attempt store behind createAttempt / getAttempt /
 * patchAttempt / listAttempts and the decisions recorded by createRecommendations
 * (src/mocks/state.ts, src/mocks/recommend.ts). When the page reports
 * `navigator.onLine === false` (Playwright's offline emulation) every handler answers
 * with a network error, since the service worker would otherwise never touch the network.
 */
import {
  http,
  HttpResponse,
  type HttpHandler,
  type HttpResponseResolver,
  type PathParams,
} from 'msw';
import { serialize } from '../lib/jcs';
import {
  mockState,
  mockUserId,
  nextMockId,
  type Attempt,
  type Decision,
  type Preferences,
} from './state';
import {
  DEFAULT_PAGE_LIMIT,
  FIXTURE_BOARD,
  MAX_PAGE_LIMIT,
  findVariant,
  queryCatalog,
  toSummary,
} from './catalog';
import { buildRecommendations, queryAttempts } from './recommend';

import getHealthLive from '../../../../contracts/examples/getHealthLive/response.json';
import getHealthReady from '../../../../contracts/examples/getHealthReady/response.json';
import getConsentDocument from '../../../../contracts/examples/getConsentDocument/response.json';
import registerUser from '../../../../contracts/examples/registerUser/response.json';
import registerUser409 from '../../../../contracts/examples/registerUser/response-409.json';
import registerUser422 from '../../../../contracts/examples/registerUser/response-422.json';
import createSession from '../../../../contracts/examples/createSession/response.json';
import createSession422 from '../../../../contracts/examples/createSession/response-422.json';
import deleteSession from '../../../../contracts/examples/deleteSession/response.json';
import listBoards from '../../../../contracts/examples/listBoards/response.json';
import listRoutes422 from '../../../../contracts/examples/listRoutes/response-422.json';
import getMe from '../../../../contracts/examples/getMe/response.json';
import putPreferences from '../../../../contracts/examples/putPreferences/response.json';
import putPreferences422 from '../../../../contracts/examples/putPreferences/response-422.json';
import createConsent from '../../../../contracts/examples/createConsent/response.json';
import deleteConsent from '../../../../contracts/examples/deleteConsent/response.json';
import createAttemptRequest from '../../../../contracts/examples/createAttempt/request.json';
import createAttempt from '../../../../contracts/examples/createAttempt/response.json';
import createAttempt409 from '../../../../contracts/examples/createAttempt/response-409.json';
import createAttempt422 from '../../../../contracts/examples/createAttempt/response-422.json';
import createAttempt422Future from '../../../../contracts/examples/createAttempt/response-422-future.json';
import getAttempt from '../../../../contracts/examples/getAttempt/response.json';
import patchAttempt409 from '../../../../contracts/examples/patchAttempt/response-409.json';
import patchAttempt422 from '../../../../contracts/examples/patchAttempt/response-422.json';
import createRecommendationsRequest from '../../../../contracts/examples/createRecommendations/request.json';
import createRecommendations from '../../../../contracts/examples/createRecommendations/response.json';
import createRecommendations422 from '../../../../contracts/examples/createRecommendations/response-422.json';
import getDecision from '../../../../contracts/examples/getDecision/response.json';

interface Fixture {
  readonly status: number;
  readonly headers?: Readonly<Record<string, string>>;
  readonly body?: unknown;
}

/** Serve a contracts/examples response fixture exactly as committed. */
const fixture = (f: Fixture): Response => {
  const init = { status: f.status, headers: f.headers ?? {} };
  return f.body === undefined
    ? new HttpResponse(null, init)
    : HttpResponse.json(f.body as Record<string, unknown>, init);
};

/**
 * Auth responses: the committed fixtures carry a fixed `expires_at` that is now in the
 * past, and the token store treats such a session as expired, so the mock issues a token
 * that lasts an hour from issue. Everything else in the fixture is served as committed.
 */
const MOCK_SESSION_TTL_MS = 60 * 60 * 1000;
const sessionFixture = (f: Fixture): Response =>
  fixture({
    ...f,
    body: {
      ...(f.body as Record<string, unknown>),
      expires_at: new Date(Date.now() + MOCK_SESSION_TTL_MS).toISOString(),
    },
  });

/** The only non-fixture response: bearer-protected operations without a token. */
const unauthenticated = (): Response =>
  HttpResponse.json(
    {
      code: 'UNAUTHENTICATED',
      message: 'Authentication required',
      request_id: 'mock-unauthenticated',
      field_errors: [],
    },
    { status: 401, headers: { 'X-Request-Id': 'mock-unauthenticated' } },
  );

const notFound = (): Response =>
  HttpResponse.json(
    {
      code: 'NOT_FOUND',
      message: 'Not found',
      request_id: 'mock-not-found',
      field_errors: [],
    },
    { status: 404, headers: { 'X-Request-Id': 'mock-not-found' } },
  );

/**
 * Envelopes the fixtures do not cover (UNSUPPORTED_ANGLE on preferences, INVALID_CURSOR
 * and UNKNOWN_BOARD on routes, VALIDATION_FAILED for max_routes > 20). Same shape as the
 * contracts/examples response-422 files, built from the request so the field error
 * names the offending field.
 */
const derived422 = (
  code:
    | 'UNSUPPORTED_ANGLE'
    | 'UNKNOWN_BOARD'
    | 'INVALID_CURSOR'
    | 'VALIDATION_FAILED',
  message: string,
  fieldErrors: { field: string; code: string; message: string }[],
): Response =>
  HttpResponse.json(
    {
      code,
      message,
      request_id: 'mock-derived-422',
      field_errors: fieldErrors,
    },
    { status: 422, headers: { 'X-Request-Id': 'mock-derived-422' } },
  );

/** Writes that need active consent (A7/A9) when the per-process profile has none. */
const consentRequired = (): Response =>
  HttpResponse.json(
    {
      code: 'CONSENT_REQUIRED',
      message: 'Active consent is required',
      request_id: 'mock-consent-required',
      field_errors: [],
    },
    { status: 403, headers: { 'X-Request-Id': 'mock-consent-required' } },
  );

const hasConsent = (): boolean =>
  mockState.me === null ||
  (mockState.me.consent !== null && mockState.me.consent.revoked_at === null);

const json = (
  body: unknown,
  status = 200,
  requestId = 'mock-store',
): Response =>
  HttpResponse.json(body as Record<string, unknown>, {
    status,
    headers: { 'X-Request-Id': requestId },
  });

/** Playwright's offline emulation: the worker would still answer, so fail like the network. */
const isPageOffline = (): boolean =>
  (globalThis.navigator as { onLine?: boolean } | undefined)?.onLine === false;

const withNetwork =
  (
    resolver: HttpResponseResolver<PathParams>,
  ): HttpResponseResolver<PathParams> =>
  (info) =>
    isPageOffline() ? HttpResponse.error() : resolver(info);

const hasBearer = (request: Request): boolean =>
  /^Bearer\s+\S+/.test(request.headers.get('Authorization') ?? '');

const readJson = async (request: Request): Promise<Record<string, unknown>> => {
  try {
    const parsed: unknown = await request.json();
    return typeof parsed === 'object' && parsed !== null
      ? (parsed as Record<string, unknown>)
      : {};
  } catch {
    return {};
  }
};

/** Guard for bearer-protected operations: no token means 401 before the resolver runs. */
const auth = (
  resolver: HttpResponseResolver<PathParams>,
): HttpResponseResolver<PathParams> =>
  withNetwork((info) =>
    hasBearer(info.request) ? resolver(info) : unauthenticated(),
  );

const USERNAME_RE = /^[a-z0-9_]{3,32}$/;

const isSupportedAngle = (angle: number): boolean =>
  FIXTURE_BOARD.available_angles.includes(angle);

const unsupportedAngle = (): Response =>
  derived422('UNSUPPORTED_ANGLE', 'Angle is not available on this board', [
    {
      field: 'angle_deg',
      code: 'enum',
      message: `must be one of ${FIXTURE_BOARD.available_angles.join(', ')}`,
    },
  ]);

const optionalNumber = (text: string | null): number | undefined => {
  if (text === null || text === '') return undefined;
  const value = Number(text);
  return Number.isFinite(value) ? value : undefined;
};
const FUTURE_TOLERANCE_MS = 5 * 60 * 1000;
const FIXTURE_ATTEMPT = getAttempt.body as Attempt;
const FIXTURE_DECISION = getDecision.body as Decision;
const FIXTURE_ATTEMPT_BODY = serialize(createAttemptRequest.body);
const FIXTURE_RECOMMENDATION_BODY = serialize(
  createRecommendationsRequest.body,
);
const MAX_ROUTES = 20;

const optionalInRange = (
  value: unknown,
  min: number,
  max: number,
  integer: boolean,
): boolean =>
  value === undefined ||
  value === null ||
  (typeof value === 'number' &&
    value >= min &&
    value <= max &&
    (!integer || Number.isInteger(value)));

/** Build an Attempt from a validated AttemptCreate body. */
const attemptFrom = (body: Record<string, unknown>, now: string): Attempt => {
  const variant = findVariant(String(body.route_variant_id));
  const summary = variant === null ? FIXTURE_ATTEMPT.route : toSummary(variant);
  return {
    attempt_id: nextMockId('attempt'),
    route_variant_id: String(body.route_variant_id),
    route: { ...summary, route_variant_id: String(body.route_variant_id) },
    occurred_at: String(body.occurred_at),
    received_at: now,
    attempts_count: body.attempts_count as number,
    sent: body.sent === true,
    completion_fraction:
      (body.completion_fraction as number | undefined) ?? null,
    perceived_effort: (body.perceived_effort as number | undefined) ?? null,
    notes: (body.notes as string | undefined) ?? null,
    revision: 1,
    status: 'ACTIVE',
  };
};

const publicHandlers: HttpHandler[] = [
  http.get('*/health/live', () => fixture(getHealthLive)),
  http.get('*/health/ready', () => fixture(getHealthReady)),
  http.get(
    '*/v1/consent/current',
    withNetwork(() => fixture(getConsentDocument)),
  ),

  http.post(
    '*/v1/auth/register',
    withNetwork(async ({ request }) => {
      const body = await readJson(request);
      const username = typeof body.username === 'string' ? body.username : '';
      if (!USERNAME_RE.test(username)) return fixture(registerUser422);
      if (mockState.registeredUsernames.has(username))
        return fixture(registerUser409);
      mockState.registeredUsernames.add(username);
      mockState.me = {
        user_id: mockUserId(username),
        username,
        consent: null,
        preferences: null,
      };
      return sessionFixture(registerUser);
    }),
  ),

  http.post(
    '*/v1/auth/session',
    withNetwork(async ({ request }) => {
      const body = await readJson(request);
      const password = typeof body.password === 'string' ? body.password : '';
      if (password.length < 8) return fixture(createSession422);
      mockState.me = null;
      return sessionFixture(createSession);
    }),
  ),
];

const protectedHandlers: HttpHandler[] = [
  http.delete(
    '*/v1/auth/session',
    auth(() => fixture(deleteSession)),
  ),
  http.get(
    '*/v1/boards',
    auth(() => fixture(listBoards)),
  ),

  http.get(
    '*/v1/routes',
    auth(({ request }) => {
      const params = new URL(request.url).searchParams;
      const limit = Number(params.get('limit') ?? String(DEFAULT_PAGE_LIMIT));
      if (limit > MAX_PAGE_LIMIT) return fixture(listRoutes422);
      const boardId = params.get('board_id') ?? undefined;
      if (boardId !== undefined && boardId !== FIXTURE_BOARD.board_id) {
        return derived422('UNKNOWN_BOARD', 'Unknown board', [
          { field: 'board_id', code: 'unknown', message: 'unknown board' },
        ]);
      }
      const angleText = params.get('angle_deg');
      const angleDeg = angleText === null ? undefined : Number(angleText);
      if (angleDeg !== undefined && !isSupportedAngle(angleDeg)) {
        return unsupportedAngle();
      }
      const result = queryCatalog({
        boardId,
        angleDeg,
        gradeMin: optionalNumber(params.get('grade_min')),
        gradeMax: optionalNumber(params.get('grade_max')),
        cursor: params.get('cursor') ?? undefined,
        limit:
          Number.isFinite(limit) && limit >= 1 ? limit : DEFAULT_PAGE_LIMIT,
      });
      if (result.kind === 'invalid_cursor') {
        return derived422('INVALID_CURSOR', 'Cursor is not valid', [
          { field: 'cursor', code: 'invalid', message: 'cursor is not valid' },
        ]);
      }
      return HttpResponse.json(
        { items: result.items, next_cursor: result.nextCursor },
        { status: 200, headers: { 'X-Request-Id': 'mock-catalog' } },
      );
    }),
  ),

  http.get(
    '*/v1/routes/:routeVariantId',
    auth(({ params }) => {
      const variant = findVariant(String(params.routeVariantId));
      return variant === null
        ? notFound()
        : HttpResponse.json(variant, {
            status: 200,
            headers: { 'X-Request-Id': 'mock-catalog' },
          });
    }),
  ),

  http.get(
    '*/v1/me',
    auth(() =>
      mockState.me === null
        ? fixture(getMe)
        : fixture({
            ...getMe,
            body: {
              ...getMe.body,
              user_id: mockState.me.user_id,
              username: mockState.me.username,
              consent: mockState.me.consent,
              preferences: mockState.me.preferences,
            },
          }),
    ),
  ),

  http.put(
    '*/v1/me/preferences',
    auth(async ({ request }) => {
      const body = await readJson(request);
      if (!Array.isArray(body.goals)) return fixture(putPreferences422);
      if (
        typeof body.angle_deg !== 'number' ||
        !isSupportedAngle(body.angle_deg)
      ) {
        return unsupportedAngle();
      }
      const preferences = body as unknown as Preferences;
      if (mockState.me !== null) mockState.me.preferences = preferences;
      return HttpResponse.json(preferences, {
        status: 200,
        headers: putPreferences.headers,
      });
    }),
  ),

  http.post(
    '*/v1/me/consent',
    auth(async ({ request }) => {
      const body = await readJson(request);
      if (mockState.me !== null) {
        const scopes =
          typeof body.scopes === 'object' && body.scopes !== null
            ? (body.scopes as Record<string, unknown>)
            : {};
        mockState.me.consent = {
          version: String(body.consent_version ?? ''),
          consented_at: new Date().toISOString(),
          revoked_at: null,
          scopes: { service: true, research: scopes.research === true },
          text_sha256: String(body.text_sha256 ?? ''),
        };
      }
      return fixture(createConsent);
    }),
  ),
  http.delete(
    '*/v1/me/consent',
    auth(() => fixture(deleteConsent)),
  ),
  http.get(
    '*/v1/me/attempts',
    auth(({ request }) => {
      const params = new URL(request.url).searchParams;
      const limit = Number(params.get('limit') ?? String(DEFAULT_PAGE_LIMIT));
      if (!Number.isInteger(limit) || limit < 1 || limit > MAX_PAGE_LIMIT) {
        return derived422('VALIDATION_FAILED', 'Request failed validation', [
          { field: 'limit', code: 'range', message: 'must be 1..100' },
        ]);
      }
      const status = params.get('status');
      const result = queryAttempts([...mockState.attempts.values()], {
        cursor: params.get('cursor') ?? undefined,
        limit,
        status:
          status === 'ACTIVE' || status === 'RETRACTED' ? status : undefined,
      });
      if (result.kind === 'invalid_cursor') {
        return derived422('INVALID_CURSOR', 'Cursor is not valid', [
          { field: 'cursor', code: 'invalid', message: 'cursor is not valid' },
        ]);
      }
      return json({ items: result.items, next_cursor: result.nextCursor });
    }),
  ),

  http.post(
    '*/v1/me/attempts',
    auth(async ({ request }) => {
      if (!hasConsent()) return consentRequired();
      const body = await readJson(request);
      const attemptsCount = body.attempts_count;
      if (
        typeof attemptsCount !== 'number' ||
        !Number.isInteger(attemptsCount) ||
        attemptsCount < 1
      ) {
        return fixture(createAttempt422);
      }
      const occurredAt = Date.parse(
        typeof body.occurred_at === 'string' ? body.occurred_at : '',
      );
      if (!Number.isFinite(occurredAt)) {
        return derived422('VALIDATION_FAILED', 'Request failed validation', [
          {
            field: 'occurred_at',
            code: 'format',
            message: 'must be a date-time',
          },
        ]);
      }
      if (occurredAt > Date.now() + FUTURE_TOLERANCE_MS) {
        return fixture(createAttempt422Future);
      }
      if (
        !optionalInRange(body.completion_fraction, 0, 1, false) ||
        !optionalInRange(body.perceived_effort, 1, 10, true) ||
        (body.notes !== undefined &&
          (typeof body.notes !== 'string' || body.notes.length > 500))
      ) {
        return derived422('VALIDATION_FAILED', 'Request failed validation', [
          {
            field: 'notes',
            code: 'range',
            message: 'check the optional fields',
          },
        ]);
      }
      const key = request.headers.get('Idempotency-Key') ?? '';
      const bodyFingerprint = serialize(body);
      const seen = mockState.attemptKeys.get(key);
      if (seen !== undefined) {
        return seen.fingerprint === bodyFingerprint
          ? json(mockState.attempts.get(seen.attemptId), 200, 'mock-replay')
          : fixture(createAttempt409);
      }
      // The committed request body maps onto the committed attempt (already seeded).
      const attempt =
        bodyFingerprint === FIXTURE_ATTEMPT_BODY
          ? (mockState.attempts.get(FIXTURE_ATTEMPT.attempt_id) ??
            (createAttempt.body as Attempt))
          : attemptFrom(body, new Date().toISOString());
      mockState.attempts.set(attempt.attempt_id, attempt);
      mockState.attemptKeys.set(key, {
        fingerprint: bodyFingerprint,
        attemptId: attempt.attempt_id,
      });
      return bodyFingerprint === FIXTURE_ATTEMPT_BODY
        ? fixture({ ...createAttempt, body: attempt })
        : json(attempt, 201, 'mock-created');
    }),
  ),

  http.get(
    '*/v1/me/attempts/:attemptId',
    auth(({ params }) => {
      const attempt = mockState.attempts.get(String(params.attemptId));
      return attempt === undefined ? notFound() : json(attempt);
    }),
  ),

  http.patch(
    '*/v1/me/attempts/:attemptId',
    auth(async ({ params, request }) => {
      const current = mockState.attempts.get(String(params.attemptId));
      if (current === undefined) return notFound();
      const body = await readJson(request);
      if (
        (body.attempts_count !== undefined &&
          !optionalInRange(
            body.attempts_count,
            1,
            Number.MAX_SAFE_INTEGER,
            true,
          )) ||
        !optionalInRange(body.completion_fraction, 0, 1, false) ||
        !optionalInRange(body.perceived_effort, 1, 10, true)
      ) {
        return fixture(patchAttempt422);
      }
      const ifMatch = request.headers.get('If-Match');
      if (ifMatch !== `"${current.revision}"`) {
        return fixture({
          ...patchAttempt409,
          body: { ...patchAttempt409.body, current_revision: current.revision },
        });
      }
      const next: Attempt = {
        ...current,
        ...(typeof body.attempts_count === 'number'
          ? { attempts_count: body.attempts_count }
          : {}),
        ...(typeof body.sent === 'boolean' ? { sent: body.sent } : {}),
        ...(typeof body.completion_fraction === 'number'
          ? { completion_fraction: body.completion_fraction }
          : {}),
        ...(typeof body.perceived_effort === 'number'
          ? { perceived_effort: body.perceived_effort }
          : {}),
        ...(typeof body.notes === 'string' ? { notes: body.notes } : {}),
        status: body.retract === true ? 'RETRACTED' : current.status,
        revision: current.revision + 1,
      };
      mockState.attempts.set(next.attempt_id, next);
      return json(next, 200, 'mock-patched');
    }),
  ),

  http.post(
    '*/v1/me/sessions/recommendations',
    auth(async ({ request }) => {
      if (!hasConsent()) return consentRequired();
      const body = await readJson(request);
      const maxRoutes =
        typeof body.max_routes === 'number' ? body.max_routes : 1;
      if (maxRoutes < 1) return fixture(createRecommendations422);
      if (maxRoutes > MAX_ROUTES) {
        return derived422('VALIDATION_FAILED', 'Request failed validation', [
          { field: 'max_routes', code: 'maximum', message: 'must be <= 20' },
        ]);
      }
      if (body.board_id !== FIXTURE_BOARD.board_id) {
        return derived422('UNKNOWN_BOARD', 'Unknown board', [
          { field: 'board_id', code: 'unknown', message: 'unknown board' },
        ]);
      }
      if (
        typeof body.angle_deg !== 'number' ||
        !isSupportedAngle(body.angle_deg)
      ) {
        return unsupportedAngle();
      }
      const createdAt = new Date().toISOString();
      if (serialize(body) === FIXTURE_RECOMMENDATION_BODY) {
        mockState.decisions.set(FIXTURE_DECISION.decision_id, FIXTURE_DECISION);
        return fixture(createRecommendations);
      }
      const response = buildRecommendations({
        decisionId: nextMockId('decision'),
        angleDeg: body.angle_deg,
        maxRoutes,
        sessionId: String(body.session_id ?? ''),
        exclude: Array.isArray(body.exclude_route_variant_ids)
          ? body.exclude_route_variant_ids.map(String)
          : [],
        attempts: [...mockState.attempts.values()],
      });
      mockState.decisions.set(response.decision_id, {
        ...response,
        created_at: createdAt,
        request: body as unknown as Decision['request'],
      });
      return json(response, 200, 'mock-decision');
    }),
  ),

  http.get(
    '*/v1/me/decisions/:decisionId',
    auth(({ params }) => {
      const id = String(params.decisionId);
      const recorded = mockState.decisions.get(id);
      if (recorded !== undefined) return json(recorded);
      return id === FIXTURE_DECISION.decision_id
        ? fixture(getDecision)
        : notFound();
    }),
  ),
];

export const handlers: HttpHandler[] = [
  ...publicHandlers,
  ...protectedHandlers,
];
