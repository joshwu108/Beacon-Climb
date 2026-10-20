/**
 * Session builder state that outlives one screen: the client session id, goal, route
 * count, ranking policy (debug), dismissed routes and the current lineup. The attempt
 * screen marks the lineup for refresh after a log, and the session tab rebuilds when it
 * regains focus. Immutable updates; components read slices through useSessionState.
 */
import { randomUUID } from 'expo-crypto';
import { useSyncExternalStore } from 'react';
import type { components } from '../../api/generated/schema';

export type Goal = components['schemas']['Goal'];
export type PolicyVersion = NonNullable<
  components['schemas']['RecommendationRequest']['policy_version']
>;
export type RecommendationResponse =
  components['schemas']['RecommendationResponse'];

/** heuristic-v2 is the server default (contract 1.3.0); v1 stays selectable for comparison. */
export const DEFAULT_POLICY: PolicyVersion = 'heuristic-v2';
export const POLICY_VERSIONS: readonly PolicyVersion[] = [
  'heuristic-v2',
  'heuristic-v1',
];

export interface SessionState {
  readonly sessionId: string;
  /** null = the first goal from preferences. */
  readonly goal: Goal | null;
  readonly routes: number;
  readonly policy: PolicyVersion;
  readonly excluded: readonly string[];
  readonly lineup: RecommendationResponse | null;
  /** Route ids of the lineup before the current one (for the NEW stamp). */
  readonly previousIds: readonly string[];
  /** Set after an attempt is logged; the session tab rebuilds once and clears it. */
  readonly refreshReason: string | null;
}

export interface SessionStore {
  getState(): SessionState;
  setState(update: (state: SessionState) => SessionState): void;
  subscribe(listener: () => void): () => void;
  reset(): void;
}

export const initialSessionState = (
  newId: () => string = randomUUID,
): SessionState => ({
  sessionId: newId(),
  goal: null,
  routes: 4,
  policy: DEFAULT_POLICY,
  excluded: [],
  lineup: null,
  previousIds: [],
  refreshReason: null,
});

export const createSessionStore = (
  newId: () => string = randomUUID,
): SessionStore => {
  let state = initialSessionState(newId);
  const listeners = new Set<() => void>();
  const emit = () => {
    for (const listener of listeners) listener();
  };
  return {
    getState: () => state,
    setState: (update) => {
      state = update(state);
      emit();
    },
    subscribe: (listener) => {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    },
    reset: () => {
      state = initialSessionState(newId);
      emit();
    },
  };
};

export const sessionStore: SessionStore = createSessionStore();

export const useSessionState = <T>(select: (state: SessionState) => T): T =>
  useSyncExternalStore(
    sessionStore.subscribe,
    () => select(sessionStore.getState()),
    () => select(sessionStore.getState()),
  );

/* Pure transitions, unit-tested. */

export const withLineup = (
  state: SessionState,
  lineup: RecommendationResponse,
  excluded: readonly string[],
): SessionState => ({
  ...state,
  excluded,
  previousIds:
    state.lineup?.recommendations.map((r) => r.route_variant_id) ?? [],
  lineup,
  refreshReason: null,
});

export const withAttemptLogged = (
  state: SessionState,
  routeName: string,
): SessionState =>
  state.lineup === null
    ? state
    : { ...state, refreshReason: `Logged ${routeName}` };
