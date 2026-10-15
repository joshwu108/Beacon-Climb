/**
 * Attempt drafts (BC-0118): the exact request body plus the Idempotency-Key it was or
 * will be sent with, kept on this device until the server has it. IndexedDB when the
 * browser offers it, an in-memory map otherwise (jsdom, private mode, open failure), so
 * every caller sees the same promise API. A key is generated once per draft and reused
 * on every retry; it is never reused for a different submission.
 */
import type { components } from '../../api/generated/schema';

export type AttemptCreate = components['schemas']['AttemptCreate'];
export type Attempt = components['schemas']['Attempt'];
type FieldError =
  components['schemas']['ErrorEnvelope']['field_errors'][number];

export type DraftState = 'draft' | 'sending' | 'synced' | 'failed';

/** Serialisable copy of an ApiError (class instances do not survive IndexedDB). */
export interface DraftError {
  readonly status: number;
  readonly code: string;
  readonly message: string;
  readonly request_id: string | null;
  readonly field_errors: readonly FieldError[];
}

export interface AttemptDraft {
  readonly draft_id: string;
  readonly idempotency_key: string;
  readonly body: AttemptCreate;
  readonly state: DraftState;
  /** Server copy once synced. */
  readonly attempt: Attempt | null;
  readonly error: DraftError | null;
  readonly created_at: string;
  /** Display only: the route name known when the draft was made. */
  readonly route_name: string | null;
}

export type DraftBackend = 'indexeddb' | 'memory';

export interface DraftStore {
  readonly backend: DraftBackend;
  /** Resolves once persisted drafts are loaded; later calls resolve immediately. */
  ready(): Promise<void>;
  /** Loaded drafts, newest first. Referentially stable until the next change. */
  list(): readonly AttemptDraft[];
  get(draftId: string): AttemptDraft | undefined;
  put(draft: AttemptDraft): Promise<void>;
  remove(draftId: string): Promise<void>;
  clear(): Promise<void>;
  subscribe(listener: () => void): () => void;
}

export const DB_NAME = 'beacon.drafts.v1';
const STORE = 'drafts';

const request = <T>(req: IDBRequest<T>): Promise<T> =>
  new Promise((resolve, reject) => {
    req.onsuccess = () => resolve(req.result);
    req.onerror = () =>
      reject(req.error ?? new Error('IndexedDB request failed'));
  });

const openDatabase = (
  factory: IDBFactory,
  name: string,
): Promise<IDBDatabase> =>
  new Promise((resolve, reject) => {
    const req = factory.open(name, 1);
    req.onupgradeneeded = () => {
      req.result.createObjectStore(STORE, { keyPath: 'draft_id' });
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error ?? new Error('IndexedDB open failed'));
    req.onblocked = () => reject(new Error('IndexedDB open blocked'));
  });

const byNewest = (a: AttemptDraft, b: AttemptDraft): number =>
  a.created_at < b.created_at ? 1 : a.created_at > b.created_at ? -1 : 0;

/** A draft that was mid-flight when the page went away has an unknown outcome. */
const INTERRUPTED: DraftError = {
  status: 0,
  code: 'UNKNOWN',
  message: 'The page closed before a reply arrived',
  request_id: null,
  field_errors: [],
};

const normalise = (draft: AttemptDraft): AttemptDraft =>
  draft.state === 'sending'
    ? { ...draft, state: 'failed', error: INTERRUPTED }
    : draft;

export interface DraftStoreOptions {
  /** `null` forces the memory backend (tests). Defaults to `globalThis.indexedDB`. */
  readonly indexedDB?: IDBFactory | null;
  readonly dbName?: string;
}

export const createDraftStore = (
  options: DraftStoreOptions = {},
): DraftStore => {
  const factory =
    options.indexedDB === undefined
      ? ((globalThis as { indexedDB?: IDBFactory }).indexedDB ?? null)
      : options.indexedDB;
  const dbName = options.dbName ?? DB_NAME;
  const cache = new Map<string, AttemptDraft>();
  const listeners = new Set<() => void>();
  let snapshot: readonly AttemptDraft[] = [];
  let db: IDBDatabase | null = null;
  let backend: DraftBackend = factory === null ? 'memory' : 'indexeddb';
  let loading: Promise<void> | null = null;

  const notify = (): void => {
    snapshot = [...cache.values()].sort(byNewest);
    for (const listener of listeners) listener();
  };

  /** Run a store transaction; a failing database degrades to memory for the session. */
  const persist = async (
    work: (store: IDBObjectStore) => IDBRequest,
  ): Promise<void> => {
    if (db === null) return;
    try {
      await request(
        work(db.transaction(STORE, 'readwrite').objectStore(STORE)),
      );
    } catch {
      db = null;
      backend = 'memory';
    }
  };

  const load = async (): Promise<void> => {
    if (factory === null) return;
    try {
      db = await openDatabase(factory, dbName);
      const rows = await request(
        db.transaction(STORE, 'readonly').objectStore(STORE).getAll(),
      );
      for (const row of rows as AttemptDraft[]) {
        cache.set(row.draft_id, normalise(row));
      }
    } catch {
      db = null;
      backend = 'memory';
    }
    notify();
  };

  return {
    get backend() {
      return backend;
    },
    ready: () => (loading ??= load()),
    list: () => snapshot,
    get: (draftId) => cache.get(draftId),
    put: async (draft) => {
      cache.set(draft.draft_id, draft);
      notify();
      await persist((store) => store.put(draft));
    },
    remove: async (draftId) => {
      cache.delete(draftId);
      notify();
      await persist((store) => store.delete(draftId));
    },
    clear: async () => {
      cache.clear();
      notify();
      await persist((store) => store.clear());
    },
    subscribe: (listener) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
  };
};

export const draftStore: DraftStore = createDraftStore();

/** Used by sign-out: drafts carry notes, so they never outlive the session on a device. */
export const clearDrafts = (): Promise<void> => draftStore.clear();
