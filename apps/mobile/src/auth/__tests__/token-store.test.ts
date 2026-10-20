import { createMemoryStorage } from '../key-value-storage';
import { SESSION_STORAGE_KEY, createTokenStore } from '../token-store';

const FUTURE = '2999-01-01T00:00:00Z';
const PAST = '2000-01-01T00:00:00Z';

describe('token store', () => {
  it('round-trips a session through the persistent storage', () => {
    const storage = createMemoryStorage();
    createTokenStore(storage).set({ token: 'opaque', expiresAt: FUTURE });
    expect(JSON.parse(storage.getItem(SESSION_STORAGE_KEY)!)).toEqual({
      token: 'opaque',
      expiresAt: FUTURE,
      userId: null,
    });
    // A fresh store (app relaunch) rehydrates from storage.
    expect(createTokenStore(storage).get()).toBe('opaque');
  });

  it('treats an expired session as absent and removes it', () => {
    const storage = createMemoryStorage();
    const store = createTokenStore(storage);
    store.set({ token: 'old', expiresAt: PAST });
    expect(store.get()).toBeNull();
    expect(storage.getItem(SESSION_STORAGE_KEY)).toBeNull();
  });

  it('remembers the user id and notifies subscribers on every change', () => {
    const store = createTokenStore(createMemoryStorage());
    const listener = jest.fn();
    store.subscribe(listener);
    store.setUserId('nobody'); // no session: ignored
    store.set({ token: 't', expiresAt: FUTURE });
    store.setUserId('user-1');
    store.setUserId('user-1'); // unchanged: no notification
    expect(store.getUserId()).toBe('user-1');
    store.clear();
    expect(store.get()).toBeNull();
    expect(listener).toHaveBeenCalledTimes(3);
  });

  it('ignores malformed stored content', () => {
    const storage = createMemoryStorage();
    storage.setItem(SESSION_STORAGE_KEY, '{"nope":true}');
    expect(createTokenStore(storage).get()).toBeNull();
    storage.setItem(SESSION_STORAGE_KEY, 'not json');
    expect(createTokenStore(storage).get()).toBeNull();
  });
});
