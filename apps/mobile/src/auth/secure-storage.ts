import * as SecureStore from 'expo-secure-store';
import { createMemoryStorage, type KeyValueStorage } from './key-value-storage';

/**
 * iOS / Android: the session token lives in the platform keychain / keystore through
 * expo-secure-store (synchronous getItem / setItem; deletion is async and fire-and-forget,
 * the in-memory copy in the token store is cleared at once).
 */
const createSecureStorage = (): KeyValueStorage => {
  const fallback = createMemoryStorage();
  return {
    getItem: (key) => {
      try {
        return SecureStore.getItem(key);
      } catch {
        return fallback.getItem(key);
      }
    },
    setItem: (key, value) => {
      try {
        SecureStore.setItem(key, value);
      } catch {
        fallback.setItem(key, value);
      }
    },
    removeItem: (key) => {
      fallback.removeItem(key);
      SecureStore.deleteItemAsync(key).catch(() => undefined);
    },
  };
};

export const platformStorage: KeyValueStorage = createSecureStorage();
