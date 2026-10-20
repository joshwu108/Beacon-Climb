import createRecommendations from '../../../../../../contracts/examples/createRecommendations/response.json';
import type { RecommendationResponse } from '../session-store';
import {
  DEFAULT_POLICY,
  createSessionStore,
  withAttemptLogged,
  withLineup,
} from '../session-store';
import { toRecommendationRequest } from '../useRecommend';

const lineup = createRecommendations.body as RecommendationResponse;
let n = 0;
const ids = () => `session-${++n}`;

describe('session store', () => {
  it('starts with heuristic-v2, four routes and no lineup', () => {
    const state = createSessionStore(ids).getState();
    expect(state.policy).toBe(DEFAULT_POLICY);
    expect(DEFAULT_POLICY).toBe('heuristic-v2');
    expect(state.routes).toBe(4);
    expect(state.lineup).toBeNull();
  });

  it('a new lineup remembers the previous route ids and clears a pending refresh', () => {
    const store = createSessionStore(ids);
    store.setState((s) => withLineup(s, lineup, []));
    store.setState((s) => withAttemptLogged(s, 'Synthetic Traverse 1'));
    expect(store.getState().refreshReason).toBe('Logged Synthetic Traverse 1');
    store.setState((s) => withLineup(s, lineup, ['x:40']));
    const state = store.getState();
    expect(state.previousIds).toEqual(
      lineup.recommendations.map((r) => r.route_variant_id),
    );
    expect(state.excluded).toEqual(['x:40']);
    expect(state.refreshReason).toBeNull();
  });

  it('logging without a lineup asks for no refresh', () => {
    const store = createSessionStore(ids);
    store.setState((s) => withAttemptLogged(s, 'anything'));
    expect(store.getState().refreshReason).toBeNull();
  });

  it('reset starts a new session id', () => {
    const store = createSessionStore(ids);
    const first = store.getState().sessionId;
    store.reset();
    expect(store.getState().sessionId).not.toBe(first);
  });
});

describe('toRecommendationRequest', () => {
  it("is apps/web's request body plus the chosen policy_version", () => {
    expect(
      toRecommendationRequest({
        boardId: 'synthetic-demo-1',
        angleDeg: 40,
        goal: 'TECHNIQUE',
        maxRoutes: 4,
        sessionId: 's-1',
        excluded: ['a:40'],
        policy: 'heuristic-v1',
      }),
    ).toEqual({
      board_id: 'synthetic-demo-1',
      angle_deg: 40,
      goal: 'TECHNIQUE',
      max_routes: 4,
      session_id: 's-1',
      exclude_route_variant_ids: ['a:40'],
      policy_version: 'heuristic-v1',
    });
  });
});
