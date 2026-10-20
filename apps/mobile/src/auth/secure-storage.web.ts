import { createMemoryStorage, type KeyValueStorage } from './key-value-storage';

/**
 * Web demo: expo-secure-store has no browser backend, so the token is kept in localStorage
 * (as the brief asks). Trade-off, as in the web app's token store: any script on the origin
 * can read it, so the demo origin must not load third-party scripts. Memory is the fallback
 * when storage is blocked (private mode, disabled site data).
 */
const createLocalStorage = (): KeyValueStorage => {
  const fallback = createMemoryStorage();
  const store = (): Storage | null => {
    try {
      return globalThis.localStorage ?? null;
    } catch {
      return null;
    }
  };
  return {
    getItem: (key) => {
      try {
        return store()?.getItem(key) ?? fallback.getItem(key);
      } catch {
        return fallback.getItem(key);
      }
    },
    setItem: (key, value) => {
      try {
        const s = store();
        if (s === null) fallback.setItem(key, value);
        else s.setItem(key, value);
      } catch {
        fallback.setItem(key, value);
      }
    },
    removeItem: (key) => {
      fallback.removeItem(key);
      try {
        store()?.removeItem(key);
      } catch {
        // Storage unavailable: the memory copy is already gone.
      }
    },
  };
};

export const platformStorage: KeyValueStorage = createLocalStorage();
