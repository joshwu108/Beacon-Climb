/**
 * Mutable state behind the MSW handlers. Deliberately tiny: just enough for the slice
 * UIs (BC-0115 to BC-0118) to exercise register/login, idempotent attempt creation and
 * optimistic-concurrency conflicts against the committed fixtures.
 */
export interface MockState {
  readonly registeredUsernames: Set<string>;
  /** Idempotency-Key -> canonical fingerprint of the first body seen under that key. */
  readonly attemptFingerprints: Map<string, string>;
  /** Current server-side revision of the single fixture attempt. */
  attemptRevision: number;
}

export const createMockState = (): MockState => ({
  registeredUsernames: new Set(),
  attemptFingerprints: new Map(),
  attemptRevision: 1,
});

export const mockState: MockState = createMockState();

export const resetMockState = (): void => {
  mockState.registeredUsernames.clear();
  mockState.attemptFingerprints.clear();
  mockState.attemptRevision = 1;
};
