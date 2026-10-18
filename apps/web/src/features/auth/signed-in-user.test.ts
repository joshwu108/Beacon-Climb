import { afterEach, describe, expect, it } from 'vitest';
import { draftStore, type AttemptDraft } from '../attempts/draft-store';
import { recordSignedInUser } from './signed-in-user';
import { tokenStore } from './token-store';

const FUTURE = '2999-01-01T00:00:00Z';
const USER_A = '018f0000-0000-7000-8000-00000000aaaa';
const USER_B = '018f0000-0000-7000-8000-00000000bbbb';

const draft = (id: string, owner: string): AttemptDraft => ({
  draft_id: id,
  idempotency_key: `key-${id}`,
  owner_user_id: owner,
  body: {
    route_variant_id: 'synthetic-route-003:35',
    occurred_at: '2026-10-14T15:00:00Z',
    attempts_count: 1,
    sent: false,
    notes: `${owner}-private`,
  },
  state: 'draft',
  attempt: null,
  error: null,
  created_at: '2026-10-14T15:00:10Z',
  route_name: null,
});

describe('recordSignedInUser', () => {
  afterEach(() => draftStore.clear());

  it("records the user for the tab and purges other accounts' drafts", async () => {
    await draftStore.put(draft('a', USER_A));
    await draftStore.put(draft('b', USER_B));
    tokenStore.set({ token: 't', expiresAt: FUTURE });
    await recordSignedInUser(USER_B);
    expect(tokenStore.getUserId()).toBe(USER_B);
    expect(draftStore.list().map((d) => d.draft_id)).toEqual(['b']);
    // The same user again (a refetch) leaves the drafts alone.
    await draftStore.put(draft('a2', USER_A));
    await recordSignedInUser(USER_B);
    expect(
      draftStore
        .list()
        .map((d) => d.draft_id)
        .sort(),
    ).toEqual(['a2', 'b']);
    // A different user signing in on the same device purges again.
    tokenStore.set({ token: 't2', expiresAt: FUTURE });
    await recordSignedInUser(USER_A);
    expect(draftStore.list().map((d) => d.draft_id)).toEqual(['a2']);
  });

  it("a re-login in a fresh tab as the same user keeps that user's drafts (BC-0123 N4)", async () => {
    await draftStore.put(draft('u1', USER_A));
    await draftStore.put(draft('u2', USER_A));
    await draftStore.put(draft('v1', USER_B));
    // A new tab: its token store starts without a user id, like after POST /v1/auth/session.
    tokenStore.set({ token: 'tab-two', expiresAt: FUTURE });
    expect(tokenStore.getUserId()).toBeNull();
    await recordSignedInUser(USER_A);
    expect(tokenStore.getUserId()).toBe(USER_A);
    expect(
      draftStore
        .list()
        .map((d) => d.draft_id)
        .sort(),
    ).toEqual(['u1', 'u2']);
  });
});
