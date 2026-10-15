/**
 * Mutable state behind the MSW handlers. Deliberately tiny: just enough for the slice
 * UIs (BC-0115 to BC-0118) to exercise register/login, consent and preferences
 * recording, idempotent attempt creation and optimistic-concurrency conflicts against
 * the committed fixtures.
 */
import type { components } from '../api/generated/schema';

export type Consent = components['schemas']['Consent'];
export type Preferences = components['schemas']['Preferences'];

/**
 * What GET /v1/me answers beyond the fixture. `null` means "serve the fixture as is"
 * (a returning user with consent and preferences). registerUser switches to a fresh
 * profile (consent and preferences null) that createConsent and putPreferences fill in,
 * so the onboarding flow can be walked end to end against the mocks (BC-0115).
 */
export interface MeOverride {
  consent: Consent | null;
  preferences: Preferences | null;
}

export interface MockState {
  readonly registeredUsernames: Set<string>;
  /** Idempotency-Key -> canonical fingerprint of the first body seen under that key. */
  readonly attemptFingerprints: Map<string, string>;
  /** Current server-side revision of the single fixture attempt. */
  attemptRevision: number;
  me: MeOverride | null;
}

export const createMockState = (): MockState => ({
  registeredUsernames: new Set(),
  attemptFingerprints: new Map(),
  attemptRevision: 1,
  me: null,
});

export const mockState: MockState = createMockState();

export const resetMockState = (): void => {
  mockState.registeredUsernames.clear();
  mockState.attemptFingerprints.clear();
  mockState.attemptRevision = 1;
  mockState.me = null;
};
