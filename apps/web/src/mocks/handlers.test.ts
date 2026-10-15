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
});
