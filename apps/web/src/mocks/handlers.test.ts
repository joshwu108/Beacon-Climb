import { describe, expect, it } from 'vitest';
import registerFixture from '../../../../contracts/examples/registerUser/response.json';
import register409 from '../../../../contracts/examples/registerUser/response-409.json';
import patch409 from '../../../../contracts/examples/patchAttempt/response-409.json';
import { handlers } from './handlers';
import { mockUserId } from './state';

const BASE = 'http://api.test';
const AUTH = {
  Authorization: 'Bearer opaque-demo-token',
  'Content-Type': 'application/json',
};

/** Every implemented operation in openapi.yaml 1.1.0 (x-planned excluded). */
const IMPLEMENTED_OPERATIONS = 19;

describe('mock handlers', () => {
  it('registers one handler per implemented operation', () => {
    expect(handlers).toHaveLength(IMPLEMENTED_OPERATIONS);
  });

  it('registerUser returns the token fixture, then USERNAME_TAKEN for the same username', async () => {
    const body = JSON.stringify({
      username: 'demo',
      password: 'demo-password',
    });
    const first = await fetch(`${BASE}/v1/auth/register`, {
      method: 'POST',
      body,
      headers: AUTH,
    });
    expect(first.status).toBe(201);
    expect(first.headers.get('X-Request-Id')).toBe(
      registerFixture.headers['X-Request-Id'],
    );
    await expect(first.json()).resolves.toEqual(registerFixture.body);
    const second = await fetch(`${BASE}/v1/auth/register`, {
      method: 'POST',
      body,
      headers: AUTH,
    });
    expect(second.status).toBe(409);
    await expect(second.json()).resolves.toEqual(register409.body);
  });

  it('createSession validates the password length with the 422 fixture', async () => {
    const res = await fetch(`${BASE}/v1/auth/session`, {
      method: 'POST',
      headers: AUTH,
      body: JSON.stringify({ username: 'demo', password: '' }),
    });
    expect(res.status).toBe(422);
  });

  it('protected operations without a bearer token are 401 UNAUTHENTICATED', async () => {
    const res = await fetch(`${BASE}/v1/boards`);
    expect(res.status).toBe(401);
    await expect(res.json()).resolves.toMatchObject({
      code: 'UNAUTHENTICATED',
    });
  });

  it('patchAttempt honours If-Match: stale revision is REVISION_CONFLICT with current_revision', async () => {
    const url = `${BASE}/v1/me/attempts/018f0000-0000-7000-8000-000000000002`;
    const patch = (ifMatch: string) =>
      fetch(url, {
        method: 'PATCH',
        headers: { ...AUTH, 'If-Match': ifMatch },
        body: JSON.stringify({ sent: false }),
      });
    expect((await patch('"1"')).status).toBe(200);
    const stale = await patch('"1"');
    expect(stale.status).toBe(409);
    await expect(stale.json()).resolves.toEqual(patch409.body);
    expect((await patch('"2"')).status).toBe(200);
  });

  it('204 fixtures are served with no body', async () => {
    const res = await fetch(`${BASE}/v1/me/consent`, {
      method: 'DELETE',
      headers: AUTH,
    });
    expect(res.status).toBe(204);
    await expect(res.text()).resolves.toBe('');
  });

  it('unknown ids are 404 NOT_FOUND', async () => {
    const res = await fetch(`${BASE}/v1/routes/nope:35`, { headers: AUTH });
    expect(res.status).toBe(404);
  });

  it('listRoutes serves the generated catalog with a next_cursor and honours filters', async () => {
    const first = await fetch(
      `${BASE}/v1/routes?board_id=synthetic-demo-1&angle_deg=35&limit=10`,
      { headers: AUTH },
    );
    expect(first.status).toBe(200);
    const page1 = (await first.json()) as {
      items: { angle_deg: number; route_variant_id: string }[];
      next_cursor: string | null;
    };
    expect(page1.items).toHaveLength(10);
    expect(page1.items.every((i) => i.angle_deg === 35)).toBe(true);
    expect(page1.next_cursor).not.toBeNull();
    const second = await fetch(
      `${BASE}/v1/routes?board_id=synthetic-demo-1&angle_deg=35&limit=10&cursor=${page1.next_cursor}`,
      { headers: AUTH },
    );
    const page2 = (await second.json()) as typeof page1;
    expect(page2.items).toHaveLength(10);
    expect(page2.next_cursor).toBeNull();
    const ids = new Set(
      [...page1.items, ...page2.items].map((i) => i.route_variant_id),
    );
    expect(ids.size).toBe(20);
  });

  it('listRoutes rejects bad input with 422 envelopes (limit fixture, derived cursor/angle/board)', async () => {
    const limit = await fetch(`${BASE}/v1/routes?limit=101`, { headers: AUTH });
    expect(limit.status).toBe(422);
    const cursor = await fetch(`${BASE}/v1/routes?cursor=***`, {
      headers: AUTH,
    });
    expect(cursor.status).toBe(422);
    await expect(cursor.json()).resolves.toMatchObject({
      code: 'INVALID_CURSOR',
    });
    const angle = await fetch(`${BASE}/v1/routes?angle_deg=50`, {
      headers: AUTH,
    });
    await expect(angle.json()).resolves.toMatchObject({
      code: 'UNSUPPORTED_ANGLE',
      field_errors: [{ field: 'angle_deg' }],
    });
    const board = await fetch(`${BASE}/v1/routes?board_id=other`, {
      headers: AUTH,
    });
    await expect(board.json()).resolves.toMatchObject({
      code: 'UNKNOWN_BOARD',
    });
  });

  it('getRoute serves generated variants with placements', async () => {
    const res = await fetch(`${BASE}/v1/routes/synthetic-route-002:35`, {
      headers: AUTH,
    });
    expect(res.status).toBe(200);
    await expect(res.json()).resolves.toMatchObject({
      route_variant_id: 'synthetic-route-002:35',
      placements: expect.arrayContaining([
        expect.objectContaining({ role: 'START' }),
      ]),
    });
  });

  it('putPreferences echoes a valid body, 422 UNSUPPORTED_ANGLE for an angle the board lacks', async () => {
    const put = (body: unknown) =>
      fetch(`${BASE}/v1/me/preferences`, {
        method: 'PUT',
        headers: AUTH,
        body: JSON.stringify(body),
      });
    const ok = await put({
      board_id: 'synthetic-demo-1',
      angle_deg: 45,
      initial_grade_band: 'NOVICE',
      goals: ['STRENGTH'],
    });
    expect(ok.status).toBe(200);
    await expect(ok.json()).resolves.toMatchObject({
      angle_deg: 45,
      goals: ['STRENGTH'],
    });
    const bad = await put({
      board_id: 'synthetic-demo-1',
      angle_deg: 50,
      initial_grade_band: 'NOVICE',
      goals: [],
    });
    expect(bad.status).toBe(422);
    await expect(bad.json()).resolves.toMatchObject({
      code: 'UNSUPPORTED_ANGLE',
      field_errors: [{ field: 'angle_deg' }],
    });
  });

  it('GET /v1/me is the fixture by default, fresh after register, and fills in as consent and preferences are recorded', async () => {
    const me = () =>
      fetch(`${BASE}/v1/me`, { headers: AUTH }).then((r) => r.json());
    await expect(me()).resolves.toMatchObject({
      preferences: { angle_deg: 35 },
    });
    await fetch(`${BASE}/v1/auth/register`, {
      method: 'POST',
      headers: AUTH,
      body: JSON.stringify({ username: 'fresh', password: 'demo-password' }),
    });
    const fresh = await me();
    expect(fresh).toMatchObject({
      username: 'fresh',
      consent: null,
      preferences: null,
    });
    // Each registration is its own user (BC-0123 F4 needs two distinguishable accounts).
    expect(fresh.user_id).not.toBe('018f0000-0000-7000-8000-000000000001');
    expect(fresh.user_id).toBe(mockUserId('fresh'));
    expect(mockUserId('other')).not.toBe(mockUserId('fresh'));
    await fetch(`${BASE}/v1/me/consent`, {
      method: 'POST',
      headers: AUTH,
      body: JSON.stringify({
        consent_version: 'v1',
        scopes: { service: true, research: true },
        text_sha256: 'a'.repeat(64),
      }),
    });
    await expect(me()).resolves.toMatchObject({
      consent: { version: 'v1', scopes: { service: true, research: true } },
      preferences: null,
    });
    await fetch(`${BASE}/v1/me/preferences`, {
      method: 'PUT',
      headers: AUTH,
      body: JSON.stringify({
        board_id: 'synthetic-demo-1',
        angle_deg: 20,
        initial_grade_band: 'EXPERT',
        goals: [],
      }),
    });
    await expect(me()).resolves.toMatchObject({
      preferences: { angle_deg: 20 },
    });
    // Signing in as the seeded user is a returning user again.
    await fetch(`${BASE}/v1/auth/session`, {
      method: 'POST',
      headers: AUTH,
      body: JSON.stringify({ username: 'demo', password: 'demo-password' }),
    });
    await expect(me()).resolves.toMatchObject({
      user_id: '018f0000-0000-7000-8000-000000000001',
      username: 'demo',
      preferences: { angle_deg: 35 },
    });
  });
});

describe('mock handlers: attempts and recommendations (BC-0117/BC-0118)', () => {
  const post = (key: string, body: unknown) =>
    fetch(`${BASE}/v1/me/attempts`, {
      method: 'POST',
      headers: { ...AUTH, 'Idempotency-Key': key },
      body: JSON.stringify(body),
    });
  const newBody = {
    route_variant_id: 'synthetic-route-002:35',
    occurred_at: '2026-10-14T16:00:00Z',
    attempts_count: 1,
    sent: false,
    notes: 'first go',
  };

  it('createAttempt is stateful per Idempotency-Key: 201, then 200 replay, then 409 for a different body', async () => {
    const first = await post('k1', newBody);
    expect(first.status).toBe(201);
    const created = (await first.json()) as {
      attempt_id: string;
      notes: string;
      revision: number;
    };
    expect(created.notes).toBe('first go');
    expect(created.revision).toBe(1);
    const replay = await post('k1', newBody);
    expect(replay.status).toBe(200);
    await expect(replay.json()).resolves.toEqual(created);
    const conflict = await post('k1', { ...newBody, sent: true });
    expect(conflict.status).toBe(409);
    await expect(conflict.json()).resolves.toMatchObject({
      code: 'IDEMPOTENCY_CONFLICT',
    });
    const get = await fetch(`${BASE}/v1/me/attempts/${created.attempt_id}`, {
      headers: AUTH,
    });
    expect(get.status).toBe(200);
  });

  it('listAttempts serves the store newest first with cursor paging and a status filter', async () => {
    await post('k2', newBody);
    const list = await fetch(`${BASE}/v1/me/attempts?limit=1`, {
      headers: AUTH,
    });
    const page1 = (await list.json()) as {
      items: { occurred_at: string }[];
      next_cursor: string;
    };
    expect(page1.items[0]!.occurred_at).toBe('2026-10-14T16:00:00Z');
    expect(page1.next_cursor).not.toBeNull();
    const page2 = (await (
      await fetch(
        `${BASE}/v1/me/attempts?limit=1&cursor=${page1.next_cursor}`,
        { headers: AUTH },
      )
    ).json()) as typeof page1;
    expect(page2.items[0]!.occurred_at).toBe('2026-10-14T14:00:00Z');
    expect(page2.next_cursor).toBeNull();
    const retracted = await fetch(`${BASE}/v1/me/attempts?status=RETRACTED`, {
      headers: AUTH,
    });
    await expect(retracted.json()).resolves.toEqual({
      items: [],
      next_cursor: null,
    });
    expect(
      (await fetch(`${BASE}/v1/me/attempts?cursor=***`, { headers: AUTH }))
        .status,
    ).toBe(422);
  });

  it('patchAttempt retracts and bumps the revision; a stale If-Match reports the current one', async () => {
    const url = `${BASE}/v1/me/attempts/018f0000-0000-7000-8000-000000000002`;
    const retract = await fetch(url, {
      method: 'PATCH',
      headers: { ...AUTH, 'If-Match': '"1"' },
      body: JSON.stringify({ retract: true }),
    });
    await expect(retract.json()).resolves.toMatchObject({
      status: 'RETRACTED',
      revision: 2,
    });
    const stale = await fetch(url, {
      method: 'PATCH',
      headers: { ...AUTH, 'If-Match': '"1"' },
      body: JSON.stringify({ sent: false }),
    });
    await expect(stale.json()).resolves.toMatchObject({
      code: 'REVISION_CONFLICT',
      current_revision: 2,
    });
  });

  it('createRecommendations is deterministic per session_id, honours excludes and records a decision', async () => {
    const body = {
      board_id: 'synthetic-demo-1',
      angle_deg: 45,
      goal: 'ENDURANCE',
      max_routes: 4,
      session_id: '33333333-3333-7333-8333-333333333333',
      exclude_route_variant_ids: [] as string[],
    };
    const recommend = (b: unknown) =>
      fetch(`${BASE}/v1/me/sessions/recommendations`, {
        method: 'POST',
        headers: AUTH,
        body: JSON.stringify(b),
      }).then(
        (r) =>
          r.json() as Promise<{
            decision_id: string;
            cold_start: boolean;
            recommendations: { route_variant_id: string; position: number }[];
          }>,
      );
    const a = await recommend(body);
    const b = await recommend(body);
    const ids = (r: typeof a) =>
      r.recommendations.map((x) => x.route_variant_id);
    expect(ids(a)).toEqual(ids(b));
    expect(a.recommendations.map((x) => x.position)).toEqual([1, 2, 3, 4]);
    expect(a.cold_start).toBe(true);
    const c = await recommend({
      ...body,
      exclude_route_variant_ids: [ids(a)[0]],
    });
    expect(ids(c)).not.toContain(ids(a)[0]);
    expect(ids(c)).toHaveLength(4);
    const decision = await fetch(`${BASE}/v1/me/decisions/${c.decision_id}`, {
      headers: AUTH,
    });
    await expect(decision.json()).resolves.toMatchObject({
      decision_id: c.decision_id,
      request: { exclude_route_variant_ids: [ids(a)[0]], goal: 'ENDURANCE' },
      recommendations: c.recommendations,
    });
  });

  it('createRecommendations serves the fixture for the fixture request and validates the rest', async () => {
    const recommend = (b: unknown) =>
      fetch(`${BASE}/v1/me/sessions/recommendations`, {
        method: 'POST',
        headers: AUTH,
        body: JSON.stringify(b),
      });
    const fixtureRequest = {
      board_id: 'synthetic-demo-1',
      angle_deg: 35,
      goal: 'TECHNIQUE',
      max_routes: 4,
      session_id: '018f0000-0000-7000-8000-000000000004',
      exclude_route_variant_ids: [],
    };
    await expect(
      recommend(fixtureRequest).then((r) => r.json()),
    ).resolves.toMatchObject({
      decision_id: '018f0000-0000-7000-8000-000000000003',
    });
    expect((await recommend({ ...fixtureRequest, max_routes: 0 })).status).toBe(
      422,
    );
    expect(
      (await recommend({ ...fixtureRequest, max_routes: 21 })).status,
    ).toBe(422);
    await expect(
      recommend({ ...fixtureRequest, angle_deg: 50 }).then((r) => r.json()),
    ).resolves.toMatchObject({ code: 'UNSUPPORTED_ANGLE' });
    await expect(
      recommend({ ...fixtureRequest, board_id: 'other' }).then((r) => r.json()),
    ).resolves.toMatchObject({ code: 'UNKNOWN_BOARD' });
  });

  it('writes answer 403 CONSENT_REQUIRED for a registered user without consent', async () => {
    await fetch(`${BASE}/v1/auth/register`, {
      method: 'POST',
      headers: AUTH,
      body: JSON.stringify({
        username: 'noconsent',
        password: 'demo-password',
      }),
    });
    const res = await post('k3', newBody);
    expect(res.status).toBe(403);
    await expect(res.json()).resolves.toMatchObject({
      code: 'CONSENT_REQUIRED',
    });
  });

  it('answers with a network error while the page reports being offline', async () => {
    const descriptor = Object.getOwnPropertyDescriptor(
      Navigator.prototype,
      'onLine',
    );
    Object.defineProperty(navigator, 'onLine', {
      configurable: true,
      get: () => false,
    });
    try {
      await expect(
        fetch(`${BASE}/v1/boards`, { headers: AUTH }),
      ).rejects.toThrow();
    } finally {
      delete (navigator as unknown as Record<string, unknown>).onLine;
      if (descriptor)
        Object.defineProperty(Navigator.prototype, 'onLine', descriptor);
    }
    expect((await fetch(`${BASE}/v1/boards`, { headers: AUTH })).status).toBe(
      200,
    );
  });
});
