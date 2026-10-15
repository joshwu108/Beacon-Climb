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
 * NOT_FOUND, the derived 422s (UNSUPPORTED_ANGLE, UNKNOWN_BOARD, INVALID_CURSOR), the
 * generated catalog pages served by listRoutes / getRoute (src/mocks/catalog.ts), the
 * echoed Preferences body from putPreferences and the per-process GET /v1/me profile.
 */
import {
  http,
  HttpResponse,
  type HttpHandler,
  type HttpResponseResolver,
  type PathParams,
} from 'msw';
import { serialize } from '../lib/jcs';
import { mockState, type Preferences } from './state';
import {
  DEFAULT_PAGE_LIMIT,
  FIXTURE_BOARD,
  MAX_PAGE_LIMIT,
  findVariant,
  queryCatalog,
} from './catalog';

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
import listAttempts from '../../../../contracts/examples/listAttempts/response.json';
import createAttempt from '../../../../contracts/examples/createAttempt/response.json';
import createAttempt200 from '../../../../contracts/examples/createAttempt/response-200.json';
import createAttempt409 from '../../../../contracts/examples/createAttempt/response-409.json';
import createAttempt422 from '../../../../contracts/examples/createAttempt/response-422.json';
import createAttempt422Future from '../../../../contracts/examples/createAttempt/response-422-future.json';
import getAttempt from '../../../../contracts/examples/getAttempt/response.json';
import patchAttempt from '../../../../contracts/examples/patchAttempt/response.json';
import patchAttempt409 from '../../../../contracts/examples/patchAttempt/response-409.json';
import patchAttempt422 from '../../../../contracts/examples/patchAttempt/response-422.json';
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
 * and UNKNOWN_BOARD on routes). Same shape as the contracts/examples response-422 files,
 * built from the request so the field error names the offending field.
 */
const derived422 = (
  code: 'UNSUPPORTED_ANGLE' | 'UNKNOWN_BOARD' | 'INVALID_CURSOR',
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
const auth =
  (
    resolver: HttpResponseResolver<PathParams>,
  ): HttpResponseResolver<PathParams> =>
  (info) =>
    hasBearer(info.request) ? resolver(info) : unauthenticated();

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
const FIXTURE_ATTEMPT_ID = getAttempt.body.attempt_id;
const FIXTURE_DECISION_ID = getDecision.body.decision_id;

const publicHandlers: HttpHandler[] = [
  http.get('*/health/live', () => fixture(getHealthLive)),
  http.get('*/health/ready', () => fixture(getHealthReady)),
  http.get('*/v1/consent/current', () => fixture(getConsentDocument)),

  http.post('*/v1/auth/register', async ({ request }) => {
    const body = await readJson(request);
    const username = typeof body.username === 'string' ? body.username : '';
    if (!USERNAME_RE.test(username)) return fixture(registerUser422);
    if (mockState.registeredUsernames.has(username))
      return fixture(registerUser409);
    mockState.registeredUsernames.add(username);
    mockState.me = { consent: null, preferences: null };
    return fixture(registerUser);
  }),

  http.post('*/v1/auth/session', async ({ request }) => {
    const body = await readJson(request);
    const password = typeof body.password === 'string' ? body.password : '';
    if (password.length < 8) return fixture(createSession422);
    mockState.me = null;
    return fixture(createSession);
  }),
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
    auth(() => fixture(listAttempts)),
  ),

  http.post(
    '*/v1/me/attempts',
    auth(async ({ request }) => {
      const body = await readJson(request);
      const attemptsCount =
        typeof body.attempts_count === 'number' ? body.attempts_count : 0;
      if (attemptsCount < 1) return fixture(createAttempt422);
      const occurredAt = Date.parse(
        typeof body.occurred_at === 'string' ? body.occurred_at : '',
      );
      if (
        Number.isFinite(occurredAt) &&
        occurredAt > Date.now() + FUTURE_TOLERANCE_MS
      ) {
        return fixture(createAttempt422Future);
      }
      const key = request.headers.get('Idempotency-Key') ?? '';
      const bodyFingerprint = serialize(body);
      const seen = mockState.attemptFingerprints.get(key);
      if (seen === undefined) {
        mockState.attemptFingerprints.set(key, bodyFingerprint);
        return fixture(createAttempt);
      }
      return seen === bodyFingerprint
        ? fixture(createAttempt200)
        : fixture(createAttempt409);
    }),
  ),

  http.get(
    '*/v1/me/attempts/:attemptId',
    auth(({ params }) =>
      params.attemptId === FIXTURE_ATTEMPT_ID
        ? fixture(getAttempt)
        : notFound(),
    ),
  ),

  http.patch(
    '*/v1/me/attempts/:attemptId',
    auth(async ({ params, request }) => {
      if (params.attemptId !== FIXTURE_ATTEMPT_ID) return notFound();
      const body = await readJson(request);
      if (typeof body.attempts_count === 'number' && body.attempts_count < 1) {
        return fixture(patchAttempt422);
      }
      const ifMatch = request.headers.get('If-Match');
      if (ifMatch !== `"${mockState.attemptRevision}"`)
        return fixture(patchAttempt409);
      mockState.attemptRevision = patchAttempt.body.revision;
      return fixture(patchAttempt);
    }),
  ),

  http.post(
    '*/v1/me/sessions/recommendations',
    auth(async ({ request }) => {
      const body = await readJson(request);
      const maxRoutes =
        typeof body.max_routes === 'number' ? body.max_routes : 1;
      return maxRoutes < 1
        ? fixture(createRecommendations422)
        : fixture(createRecommendations);
    }),
  ),

  http.get(
    '*/v1/me/decisions/:decisionId',
    auth(({ params }) =>
      params.decisionId === FIXTURE_DECISION_ID
        ? fixture(getDecision)
        : notFound(),
    ),
  ),
];

export const handlers: HttpHandler[] = [
  ...publicHandlers,
  ...protectedHandlers,
];
