/**
 * Mutable state behind the MSW handlers. Deliberately tiny: just enough for the slice
 * UIs (BC-0115 to BC-0118) to exercise register/login, consent and preferences
 * recording, idempotent attempt creation, corrections with optimistic concurrency and
 * recorded recommendation decisions against the committed fixtures.
 */
import type { components } from '../api/generated/schema';
import listAttempts from '../../../../contracts/examples/listAttempts/response.json';

export type Consent = components['schemas']['Consent'];
export type Preferences = components['schemas']['Preferences'];
export type Attempt = components['schemas']['Attempt'];
export type Decision = components['schemas']['Decision'];

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

/** First request seen under an Idempotency-Key: its canonical body and the attempt it made. */
export interface IdempotencyRecord {
  readonly fingerprint: string;
  readonly attemptId: string;
}

export interface MockState {
  readonly registeredUsernames: Set<string>;
  /** Idempotency-Key -> first body fingerprint and resulting attempt (createAttempt). */
  readonly attemptKeys: Map<string, IdempotencyRecord>;
  /** Every attempt the mock knows, by attempt_id; seeded with the listAttempts fixture rows. */
  readonly attempts: Map<string, Attempt>;
  /** Decisions recorded by createRecommendations, by decision_id (getDecision serves them). */
  readonly decisions: Map<string, Decision>;
  /** Counter behind generated attempt and decision ids. */
  nextId: number;
  me: MeOverride | null;
}

const FIXTURE_ATTEMPTS = listAttempts.body.items as Attempt[];

const seedAttempts = (): Map<string, Attempt> =>
  new Map(FIXTURE_ATTEMPTS.map((attempt) => [attempt.attempt_id, attempt]));

export const createMockState = (): MockState => ({
  registeredUsernames: new Set(),
  attemptKeys: new Map(),
  attempts: seedAttempts(),
  decisions: new Map(),
  nextId: 1,
  me: null,
});

export const mockState: MockState = createMockState();

export const resetMockState = (): void => {
  mockState.registeredUsernames.clear();
  mockState.attemptKeys.clear();
  mockState.attempts.clear();
  for (const [id, attempt] of seedAttempts())
    mockState.attempts.set(id, attempt);
  mockState.decisions.clear();
  mockState.nextId = 1;
  mockState.me = null;
};

/** Deterministic uuid-shaped ids: the fixture prefix with a counter in the last block. */
export const nextMockId = (kind: 'attempt' | 'decision'): string => {
  const n = mockState.nextId++;
  const tag = kind === 'attempt' ? 'a' : 'd';
  return `018f0000-0000-7000-8000-${tag}${String(n).padStart(11, '0')}`;
};
