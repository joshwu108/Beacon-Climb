/**
 * Session token store (mobile). Same shape and rules as apps/web's token store: memory
 * first, mirrored to persistent storage, an expired session reads as absent, and the user
 * id (from GET /v1/me) is remembered alongside the token. The persistent store is
 * expo-secure-store on iOS / Android and localStorage on web (see secure-storage*.ts).
 */
import type { KeyValueStorage } from './key-value-storage';
import { platformStorage } from './secure-storage';

export interface Session {
  readonly token: string;
  /** RFC 3339 timestamp from AuthResponse.expires_at. */
  readonly expiresAt: string;
  readonly userId: string | null;
}

export interface NewSession {
  readonly token: string;
  readonly expiresAt: string;
  readonly userId?: string | null;
}

export const SESSION_STORAGE_KEY = 'beacon.session.v1';

export interface TokenStore {
  get(): string | null;
  getSession(): Session | null;
  getUserId(): string | null;
  set(session: NewSession): void;
  setUserId(userId: string): void;
  clear(): void;
  subscribe(listener: () => void): () => void;
}

const parse = (raw: string | null): Session | null => {
  if (!raw) return null;
  try {
    const value: unknown = JSON.parse(raw);
    if (typeof value !== 'object' || value === null) return null;
    const { token, expiresAt, userId } = value as Partial<Session>;
    if (typeof token !== 'string' || typeof expiresAt !== 'string') return null;
    return {
      token,
      expiresAt,
      userId: typeof userId === 'string' ? userId : null,
    };
  } catch {
    return null;
  }
};

export const createTokenStore = (
  storage: KeyValueStorage,
  now: () => number = Date.now,
): TokenStore => {
  let memory: Session | null = parse(storage.getItem(SESSION_STORAGE_KEY));
  const listeners = new Set<() => void>();

  const persist = (session: Session | null): void => {
    if (session === null) storage.removeItem(SESSION_STORAGE_KEY);
    else storage.setItem(SESSION_STORAGE_KEY, JSON.stringify(session));
  };

  const write = (session: Session | null): void => {
    memory = session;
    persist(session);
    for (const listener of listeners) listener();
  };

  const getSession = (): Session | null => {
    if (memory === null) return null;
    const expiresAt = Date.parse(memory.expiresAt);
    if (Number.isFinite(expiresAt) && expiresAt <= now()) {
      memory = null;
      persist(null);
    }
    return memory;
  };

  return {
    get: () => getSession()?.token ?? null,
    getSession,
    getUserId: () => getSession()?.userId ?? null,
    set: (session) =>
      write({
        token: session.token,
        expiresAt: session.expiresAt,
        userId: session.userId ?? null,
      }),
    setUserId: (userId) => {
      const session = getSession();
      if (session === null || session.userId === userId) return;
      write({ ...session, userId });
    },
    clear: () => write(null),
    subscribe: (listener) => {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    },
  };
};

export const tokenStore: TokenStore = createTokenStore(platformStorage);
