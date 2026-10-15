import { describe, expect, it, vi } from 'vitest';
import { createDraftStore, type AttemptDraft } from './draft-store';

const draft = (
  id: string,
  overrides: Partial<AttemptDraft> = {},
): AttemptDraft => ({
  draft_id: id,
  idempotency_key: `key-${id}`,
  body: {
    route_variant_id: 'synthetic-route-001:35',
    occurred_at: '2026-10-14T14:00:00Z',
    attempts_count: 1,
    sent: false,
  },
  state: 'draft',
  attempt: null,
  error: null,
  created_at: `2026-10-14T14:0${id.length}:00Z`,
  route_name: null,
  ...overrides,
});

describe('draft store (memory fallback)', () => {
  it('falls back to memory when IndexedDB is unavailable (jsdom)', async () => {
    const store = createDraftStore();
    await store.ready();
    expect(store.backend).toBe('memory');
    expect(store.list()).toEqual([]);
  });

  it('round-trips put, get, list (newest first), remove and clear, notifying subscribers', async () => {
    const store = createDraftStore({ indexedDB: null });
    const listener = vi.fn();
    store.subscribe(listener);
    await store.ready();
    await store.put(draft('a'));
    await store.put(draft('bb'));
    expect(store.get('a')?.idempotency_key).toBe('key-a');
    expect(store.list().map((d) => d.draft_id)).toEqual(['bb', 'a']);
    const snapshot = store.list();
    expect(store.list()).toBe(snapshot);
    await store.put({ ...draft('a'), state: 'failed' });
    expect(store.list()).not.toBe(snapshot);
    expect(store.get('a')?.state).toBe('failed');
    await store.remove('bb');
    expect(store.list().map((d) => d.draft_id)).toEqual(['a']);
    await store.clear();
    expect(store.list()).toEqual([]);
    expect(listener).toHaveBeenCalledTimes(5);
  });

  it('keeps the same key for a draft across updates', async () => {
    const store = createDraftStore({ indexedDB: null });
    await store.put(draft('x'));
    const key = store.get('x')!.idempotency_key;
    await store.put({ ...store.get('x')!, state: 'sending' });
    await store.put({ ...store.get('x')!, state: 'failed' });
    expect(store.get('x')!.idempotency_key).toBe(key);
  });
});
