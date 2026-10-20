import { resetMockState } from '@web/mocks/state';
import { handleMockRequest } from '../mock-fetch';
import { holdOverlapDistance, similarFor } from '../similar';
import { CATALOG, findVariant } from '@web/mocks/catalog';

const BASE = 'http://mock.beacon.local';
const auth = { Authorization: 'Bearer mock-token' };

describe('mock transport', () => {
  beforeEach(() => resetMockState());

  it("serves apps/web's handlers in-process (register, then GET /v1/me)", async () => {
    const register = await handleMockRequest(
      new Request(`${BASE}/v1/auth/register`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          username: 'chalk_hands',
          password: 'long-enough',
        }),
      }),
    );
    expect(register.status).toBe(201);
    const me = await handleMockRequest(
      new Request(`${BASE}/v1/me`, { headers: auth }),
    );
    expect(me.status).toBe(200);
    expect(await me.json()).toMatchObject({
      username: 'chalk_hands',
      consent: null,
      preferences: null,
    });
  });

  it('answers 401 without a bearer token and 501 for an unmocked path', async () => {
    expect(
      (await handleMockRequest(new Request(`${BASE}/v1/boards`))).status,
    ).toBe(401);
    const planned = await handleMockRequest(
      new Request(`${BASE}/v1/me/skill-memory`, { headers: auth }),
    );
    expect(planned.status).toBe(501);
  });

  it('serves the committed listSimilarRoutes fixture for its route', async () => {
    const response = await handleMockRequest(
      new Request(`${BASE}/v1/routes/synthetic-route-001:40/similar?limit=2`, {
        headers: auth,
      }),
    );
    expect(response.status).toBe(200);
    const body = await response.json();
    expect(body.model_version).toBe('model-a-deepsets-fixture');
    expect(body.items.map((i: { distance: number }) => i.distance)).toEqual([
      0.08, 0.31,
    ]);
  });

  it('rejects a limit outside 1..50 and an unknown route', async () => {
    const tooMany = await handleMockRequest(
      new Request(`${BASE}/v1/routes/synthetic-route-002:40/similar?limit=51`, {
        headers: auth,
      }),
    );
    expect(tooMany.status).toBe(422);
    const unknown = await handleMockRequest(
      new Request(`${BASE}/v1/routes/nope:40/similar`, { headers: auth }),
    );
    expect(unknown.status).toBe(404);
  });
});

describe('similarFor (generated neighbours)', () => {
  it('stays on the same board and angle, nearest first, never the route itself', () => {
    const route = findVariant('synthetic-route-004:40')!;
    const result = similarFor(route, 5);
    expect(result.items).toHaveLength(5);
    for (const item of result.items) {
      expect(item.route.angle_deg).toBe(40);
      expect(item.route.route_variant_id).not.toBe(route.route_variant_id);
    }
    const distances = result.items.map((i) => i.distance);
    expect([...distances].sort((a, b) => a - b)).toEqual(distances);
  });

  it('distance is 0 for identical hold sets and 1 for disjoint ones', () => {
    const route = CATALOG[1]!;
    expect(holdOverlapDistance(route, route)).toBe(0);
    expect(holdOverlapDistance(route, { ...route, placements: [] })).toBe(1);
  });
});
