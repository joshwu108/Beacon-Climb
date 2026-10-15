/**
 * Session token store.
 *
 * The token lives in memory first. It is mirrored to sessionStorage so a reload of the
 * same tab keeps the session; sessionStorage is per-tab and cleared when the tab closes.
 * Trade-off (BC-0115, ADR-0005): sessionStorage is readable by any script on the origin,
 * so the app must keep a strict CSP and never embed third-party scripts. localStorage is
 * deliberately not used (it would outlive the tab and be shared across tabs).
 *
 * The session also remembers which user the token belongs to (`userId`, from GET /v1/me)
 * once that is known. Attempt drafts are stamped with it and only shown to, retried for
 * and sent by that user (BC-0123 F4), so a draft left behind on a shared device never
 * reaches the next account. The id is per tab like the token: clearing the token clears it.
 */

export interface Session {
  readonly token: string;
  /** RFC 3339 timestamp from AuthResponse.expires_at. */
  readonly expiresAt: string;
  /** `Me.user_id` once GET /v1/me has answered for this token; null until then. */
  readonly userId: string | null;
}

export interface NewSession {
  readonly token: string;
  readonly expiresAt: string;
  readonly userId?: string | null;
}

const STORAGE_KEY = 'beacon.session.v1';

const readStorage = (): Session | null => {
  try {
    const raw = globalThis.sessionStorage?.getItem(STORAGE_KEY);
    if (!raw) return null;
    const parsed: unknown = JSON.parse(raw);
    if (
      typeof parsed === 'object' &&
      parsed !== null &&
      typeof (parsed as Session).token === 'string' &&
      typeof (parsed as Session).expiresAt === 'string'
    ) {
      const userId = (parsed as Partial<Session>).userId;
      return {
        token: (parsed as Session).token,
        expiresAt: (parsed as Session).expiresAt,
        userId: typeof userId === 'string' ? userId : null,
      };
    }
    return null;
  } catch {
    return null;
  }
};

const writeStorage = (session: Session | null): void => {
  try {
    if (session === null) globalThis.sessionStorage?.removeItem(STORAGE_KEY);
    else
      globalThis.sessionStorage?.setItem(STORAGE_KEY, JSON.stringify(session));
  } catch {
    // Storage can be unavailable (private mode, quota, disabled). Memory still works.
  }
};

const isExpired = (session: Session, now: () => number): boolean => {
  const expiresAt = Date.parse(session.expiresAt);
  return Number.isFinite(expiresAt) && expiresAt <= now();
};

export interface TokenStore {
  /** Current bearer token, or null when absent or expired. */
  get(): string | null;
  /** Current session, or null when absent or expired. */
  getSession(): Session | null;
  /** The signed-in user's id, or null while unknown or signed out. */
  getUserId(): string | null;
  /** Start a session; `userId` is null unless the caller already knows it. */
  set(session: NewSession): void;
  /** Record which user the current token belongs to. No session: no-op. */
  setUserId(userId: string): void;
  clear(): void;
  /** Notified after set, setUserId and clear. */
  subscribe(listener: () => void): () => void;
}

export const createTokenStore = (now: () => number = Date.now): TokenStore => {
  let memory: Session | null = readStorage();
  const listeners = new Set<() => void>();

  const notify = (): void => {
    for (const listener of listeners) listener();
  };

  const getSession = (): Session | null => {
    if (memory !== null && isExpired(memory, now)) {
      memory = null;
      writeStorage(null);
    }
    return memory;
  };

  const write = (session: Session | null): void => {
    memory = session;
    writeStorage(session);
    notify();
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
      return () => listeners.delete(listener);
    },
  };
};

export const tokenStore: TokenStore = createTokenStore();

export { STORAGE_KEY as SESSION_STORAGE_KEY };
