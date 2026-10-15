import { describe, expect, it } from 'vitest';
import registerFixture from '../../../../contracts/examples/registerUser/response.json';
import register409 from '../../../../contracts/examples/registerUser/response-409.json';
import patch409 from '../../../../contracts/examples/patchAttempt/response-409.json';
import { handlers } from './handlers';

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
    await expect(me()).resolves.toMatchObject({
      consent: null,
      preferences: null,
    });
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
      preferences: { angle_deg: 35 },
    });
  });
});
