import { QueryClient } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { server } from '../../mocks/server';
import { tokenStore } from '../auth/token-store';
import { submitDraft, syncFailedDrafts } from './attempt-sync';
import { draftStore, type AttemptDraft } from './draft-store';

const FUTURE = '2999-01-01T00:00:00Z';
const DEMO_USER = '018f0000-0000-7000-8000-000000000001';
const OTHER_USER = '018f0000-0000-7000-8000-00000000beef';

const failed = (id: string, owner: string, notes = 'ok'): AttemptDraft => ({
  draft_id: id,
  idempotency_key: `key-${id}`,
  owner_user_id: owner,
  body: {
    route_variant_id: 'synthetic-route-003:35',
    occurred_at: '2026-10-14T15:00:00Z',
    attempts_count: 1,
    sent: false,
    notes,
  },
  state: 'failed',
  attempt: null,
  error: {
    status: 0,
    code: 'UNKNOWN',
    message: 'Network request failed',
    request_id: null,
    field_errors: [],
  },
  created_at: `2026-10-14T15:00:${id.length.toString().padStart(2, '0')}Z`,
  route_name: null,
});

const capturePostKeys = (): string[] => {
  const keys: string[] = [];
  server.events.on('request:start', ({ request }) => {
    if (
      request.method === 'POST' &&
      new URL(request.url).pathname === '/v1/me/attempts'
    )
      keys.push(request.headers.get('Idempotency-Key') ?? '');
  });
  return keys;
};

describe('attempt sync and draft ownership (BC-0123 F4)', () => {
  beforeEach(() => server.events.removeAllListeners());
  afterEach(() => draftStore.clear());

  it("syncFailedDrafts sends only the signed-in user's drafts", async () => {
    const keys = capturePostKeys();
    await draftStore.put(failed('mine', DEMO_USER));
    await draftStore.put(failed('theirs', OTHER_USER, 'A-private'));
    tokenStore.set({ token: 'opaque-demo-token', expiresAt: FUTURE });
    tokenStore.setUserId(DEMO_USER);
    const results = await syncFailedDrafts(new QueryClient());
    expect(results.map((d) => d.draft_id)).toEqual(['mine']);
    expect(results[0]!.state).toBe('synced');
    expect(keys).toEqual(['key-mine']);
    expect(draftStore.get('theirs')?.state).toBe('failed');
  });

  it('syncFailedDrafts sends nothing while the user is unknown', async () => {
    const keys = capturePostKeys();
    await draftStore.put(failed('mine', DEMO_USER));
    tokenStore.set({ token: 'opaque-demo-token', expiresAt: FUTURE });
    expect(await syncFailedDrafts(new QueryClient())).toEqual([]);
    expect(keys).toEqual([]);
  });

  it("submitDraft refuses another user's draft without a request", async () => {
    const keys = capturePostKeys();
    await draftStore.put(failed('theirs', OTHER_USER, 'A-private'));
    tokenStore.set({ token: 'opaque-demo-token', expiresAt: FUTURE });
    tokenStore.setUserId(DEMO_USER);
    await expect(submitDraft('theirs')).rejects.toThrow(
      /does not belong to the signed-in user/,
    );
    expect(keys).toEqual([]);
    expect(draftStore.get('theirs')?.state).toBe('failed');
  });
});
