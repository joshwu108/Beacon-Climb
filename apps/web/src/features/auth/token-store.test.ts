import { beforeEach, describe, expect, it, vi } from 'vitest';
import { SESSION_STORAGE_KEY, createTokenStore } from './token-store';

const FUTURE = '2999-01-01T00:00:00Z';
const PAST = '2000-01-01T00:00:00Z';

describe('token store', () => {
  beforeEach(() => sessionStorage.clear());

  it('starts empty', () => {
    const store = createTokenStore();
    expect(store.get()).toBeNull();
    expect(store.getSession()).toBeNull();
  });

  it('round-trips a session through memory and sessionStorage', () => {
    const store = createTokenStore();
    store.set({ token: 'opaque-demo-token', expiresAt: FUTURE });
    expect(store.get()).toBe('opaque-demo-token');
    expect(JSON.parse(sessionStorage.getItem(SESSION_STORAGE_KEY)!)).toEqual({
      token: 'opaque-demo-token',
      expiresAt: FUTURE,
      userId: null,
    });
    // A fresh store (same tab after reload) rehydrates from sessionStorage.
    expect(createTokenStore().get()).toBe('opaque-demo-token');
  });

  it('clears memory and storage', () => {
    const store = createTokenStore();
    store.set({ token: 't', expiresAt: FUTURE });
    store.clear();
    expect(store.get()).toBeNull();
    expect(sessionStorage.getItem(SESSION_STORAGE_KEY)).toBeNull();
  });

  it('treats an expired session as absent and drops it', () => {
    const store = createTokenStore(() => Date.parse('2030-01-01T00:00:00Z'));
    store.set({ token: 'old', expiresAt: PAST });
    expect(store.get()).toBeNull();
    expect(sessionStorage.getItem(SESSION_STORAGE_KEY)).toBeNull();
  });

  it('remembers the user id once known, per tab, and drops it with the token', () => {
    const store = createTokenStore();
    const listener = vi.fn();
    store.subscribe(listener);
    store.setUserId('nobody');
    expect(store.getUserId()).toBeNull();
    expect(listener).not.toHaveBeenCalled();
    store.set({ token: 't', expiresAt: FUTURE });
    expect(store.getUserId()).toBeNull();
    store.setUserId('user-1');
    store.setUserId('user-1');
    expect(store.getUserId()).toBe('user-1');
    expect(listener).toHaveBeenCalledTimes(2);
    expect(createTokenStore().getUserId()).toBe('user-1');
    // A new sign-in starts with an unknown user again.
    store.set({ token: 't2', expiresAt: FUTURE });
    expect(store.getUserId()).toBeNull();
    store.set({ token: 't3', expiresAt: FUTURE, userId: 'user-2' });
    expect(store.getUserId()).toBe('user-2');
    store.clear();
    expect(store.getUserId()).toBeNull();
    expect(listener).toHaveBeenCalledTimes(5);
  });

  it('reads a session stored before the user id existed', () => {
    sessionStorage.setItem(
      SESSION_STORAGE_KEY,
      JSON.stringify({ token: 'old-shape', expiresAt: FUTURE }),
    );
    const store = createTokenStore();
    expect(store.get()).toBe('old-shape');
    expect(store.getUserId()).toBeNull();
  });

  it('ignores malformed storage content', () => {
    sessionStorage.setItem(SESSION_STORAGE_KEY, '{"nope":true}');
    expect(createTokenStore().get()).toBeNull();
    sessionStorage.setItem(SESSION_STORAGE_KEY, 'not json');
    expect(createTokenStore().get()).toBeNull();
  });
});
