import createRecommendationsRequest from '../../../../../contracts/examples/createRecommendations/request.json';
import { mockState, resetMockState } from '@web/mocks/state';
import { handleMockRequest } from '../mock-fetch';

const recommend = (body: Record<string, unknown>) =>
  handleMockRequest(
    new Request('http://mock.beacon.local/v1/me/sessions/recommendations', {
      method: 'POST',
      headers: {
        Authorization: 'Bearer t',
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(body),
    }),
  );

describe('mock policy echo', () => {
  beforeEach(() => resetMockState());

  it('reports heuristic-v2 when no policy is asked for (the 1.3.0 default)', async () => {
    const body = { ...createRecommendationsRequest.body, max_routes: 3 };
    const response = await recommend(body);
    expect(response.status).toBe(200);
    expect((await response.json()).policy_version).toBe('heuristic-v2');
  });

  it('echoes heuristic-v1 on the response and on the recorded decision', async () => {
    const response = await recommend({
      ...createRecommendationsRequest.body,
      max_routes: 3,
      policy_version: 'heuristic-v1',
    });
    const body = await response.json();
    expect(body.policy_version).toBe('heuristic-v1');
    expect(mockState.decisions.get(body.decision_id)?.policy_version).toBe(
      'heuristic-v1',
    );
  });

  it('leaves error responses alone', async () => {
    const response = await recommend({
      ...createRecommendationsRequest.body,
      max_routes: 0,
    });
    expect(response.status).toBe(422);
  });
});
