/**
 * Session token store.
 *
 * The token lives in memory first. It is mirrored to sessionStorage so a reload of the
 * same tab keeps the session; sessionStorage is per-tab and cleared when the tab closes.
 * Trade-off (BC-0115, ADR-0005): sessionStorage is readable by any script on the origin,
 * so the app must keep a strict CSP and never embed third-party scripts. localStorage is
 * deliberately not used (it would outlive the tab and be shared across tabs).
 */

export interface Session {
  readonly token: string;
  /** RFC 3339 timestamp from AuthResponse.expires_at. */
  readonly expiresAt: string;
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
      return {
        token: (parsed as Session).token,
        expiresAt: (parsed as Session).expiresAt,
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
  set(session: Session): void;
  clear(): void;
}

export const createTokenStore = (now: () => number = Date.now): TokenStore => {
  let memory: Session | null = readStorage();

  const getSession = (): Session | null => {
    if (memory !== null && isExpired(memory, now)) {
      memory = null;
      writeStorage(null);
    }
    return memory;
  };

  return {
    get: () => getSession()?.token ?? null,
    getSession,
    set: (session) => {
      memory = { token: session.token, expiresAt: session.expiresAt };
      writeStorage(memory);
    },
    clear: () => {
      memory = null;
      writeStorage(null);
    },
  };
};

export const tokenStore: TokenStore = createTokenStore();

export { STORAGE_KEY as SESSION_STORAGE_KEY };
