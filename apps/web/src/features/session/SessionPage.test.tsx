import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { beforeEach, describe, expect, it } from 'vitest';
import meFixture from '../../../../../contracts/examples/getMe/response.json';
import recommendationsFixture from '../../../../../contracts/examples/createRecommendations/response.json';
import { server } from '../../mocks/server';
import { expectNoBlockingViolations } from '../../test/axe';
import { renderApp } from '../../test/render';
import { tokenStore } from '../auth/token-store';

const FUTURE = '2999-01-01T00:00:00Z';
const signIn = (): void =>
  tokenStore.set({ token: 'opaque-demo-token', expiresAt: FUTURE });

interface Captured {
  readonly body: Record<string, unknown>;
}

const captureRecommendationRequests = (): Captured[] => {
  const captured: Captured[] = [];
  server.events.on('request:start', ({ request }) => {
    if (new URL(request.url).pathname === '/v1/me/sessions/recommendations') {
      void request
        .clone()
        .json()
        .then((body) =>
          captured.push({ body: body as Record<string, unknown> }),
        );
    }
  });
  return captured;
};

/** Top-level cards only (reason chips are nested lists). */
const cards = (): HTMLElement[] =>
  Array.from(
    screen
      .getByRole('list', { name: 'Recommended routes in order' })
      .querySelectorAll<HTMLElement>(':scope > li'),
  );

const cardNames = () =>
  cards().map(
    (card) => within(card).getByRole('heading', { level: 3 }).textContent,
  );

const build = async (user: ReturnType<typeof userEvent.setup>) => {
  signIn();
  const rendered = renderApp('/session');
  await screen.findByRole('form', { name: 'Session setup' });
  await user.click(screen.getByRole('button', { name: 'Build my session' }));
  await screen.findByRole('heading', {
    level: 2,
    name: /your session: 4 routes/i,
  });
  return rendered;
};

describe('SessionPage', () => {
  beforeEach(() => server.events.removeAllListeners());

  it('sends board and angle from preferences with a session id kept in the URL, and shows the cold-start notice', async () => {
    const user = userEvent.setup();
    const requests = captureRecommendationRequests();
    const { container } = await build(user);
    await waitFor(() => expect(requests).toHaveLength(1));
    const body = requests[0]!.body;
    expect(body).toMatchObject({
      board_id: meFixture.body.preferences.board_id,
      angle_deg: meFixture.body.preferences.angle_deg,
      goal: 'TECHNIQUE',
      max_routes: 4,
      exclude_route_variant_ids: [],
    });
    expect(body.session_id).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/,
    );
    expect(
      screen.getByText(
        /recommendations are heuristic until you log a few attempts/i,
      ),
    ).toBeVisible();
    expect(cards()).toHaveLength(4);
    expect(screen.getAllByText('No personalised estimate yet')).toHaveLength(4);
    expect(screen.getAllByText('Not enough history')).toHaveLength(4);
    expect(
      screen.getByRole('link', { name: 'Why these routes' }),
    ).toHaveAttribute(
      'href',
      expect.stringMatching(/^\/decisions\/018f0000-0000-7000-8000-d/),
    );
    expect(
      within(cards()[0]!).getByRole('link', { name: 'Log attempt' }),
    ).toHaveAttribute(
      'href',
      expect.stringMatching(
        /^\/attempts\/new\?route_variant_id=synthetic-route-/,
      ),
    );
    // BC-0310 freshness badge: the mock lineup is a synchronous recompute.
    const freshness = screen.getByRole('note', {
      name: /^Features as of .+, recomputed on request\.$/,
    });
    expect(freshness).toHaveTextContent(
      /^features as of \d\d:\d\d\s*recomputed$/,
    );
    expect(freshness.querySelector('time')).toHaveAttribute(
      'dateTime',
      recommendationsFixture.body.feature_as_of,
    );
    // The lineup heading takes focus so the result is announced.
    expect(
      screen.getByRole('heading', { level: 2, name: /your session/i }),
    ).toHaveFocus();
    await expectNoBlockingViolations(container);
  });

  it('reuses the session id from the URL on reload', async () => {
    const user = userEvent.setup();
    const requests = captureRecommendationRequests();
    signIn();
    renderApp(
      '/session?session_id=22222222-2222-7222-8222-222222222222&goal=STRENGTH&routes=5',
    );
    await screen.findByRole('form', { name: 'Session setup' });
    expect(screen.getByRole('radio', { name: /strength/i })).toBeChecked();
    expect(screen.getByLabelText('Routes in the session')).toHaveValue(5);
    await user.click(screen.getByRole('button', { name: 'Build my session' }));
    await waitFor(() => expect(requests).toHaveLength(1));
    expect(requests[0]!.body).toMatchObject({
      session_id: '22222222-2222-7222-8222-222222222222',
      goal: 'STRENGTH',
      max_routes: 5,
    });
  });

  it('Dismiss re-requests with the route excluded and it does not come back; Swap keeps the count', async () => {
    const user = userEvent.setup();
    const requests = captureRecommendationRequests();
    await build(user);
    const before = cardNames();
    const dismissed = cards()[1]!;
    const dismissedName = within(dismissed).getByRole('heading', {
      level: 3,
    }).textContent;
    await user.click(
      within(dismissed).getByRole('button', { name: 'Dismiss' }),
    );
    await screen.findByRole('heading', {
      level: 2,
      name: /your session: 3 routes/i,
    });
    await waitFor(() => expect(requests).toHaveLength(2));
    const excluded = requests[1]!.body.exclude_route_variant_ids as string[];
    expect(excluded).toHaveLength(1);
    expect(requests[1]!.body.session_id).toBe(requests[0]!.body.session_id);
    expect(requests[1]!.body.max_routes).toBe(3);
    expect(cardNames()).not.toContain(dismissedName);
    expect(cardNames()).toHaveLength(3);
    expect(screen.getByText(/dismissed: 1 route/i)).toBeVisible();

    await user.click(within(cards()[0]!).getByRole('button', { name: 'Swap' }));
    await waitFor(() => expect(requests).toHaveLength(3));
    expect(requests[2]!.body.max_routes).toBe(3);
    expect(requests[2]!.body.exclude_route_variant_ids).toHaveLength(2);
    await waitFor(() =>
      expect(screen.getByText(/dismissed: 2 routes/i)).toBeVisible(),
    );
    expect(cardNames()).toHaveLength(3);
    expect(cardNames()).not.toContain(before[0]);
    expect(screen.getAllByText('New').length).toBeGreaterThan(0);
  });

  it('shows the simplified-recommendation badge when fallback_used is true', async () => {
    const user = userEvent.setup();
    server.use(
      http.post('*/v1/me/sessions/recommendations', () =>
        HttpResponse.json(
          { ...recommendationsFixture.body, fallback_used: true },
          { status: 200 },
        ),
      ),
    );
    signIn();
    renderApp('/session');
    await screen.findByRole('form', { name: 'Session setup' });
    await user.click(screen.getByRole('button', { name: 'Build my session' }));
    await screen.findByRole('heading', {
      level: 2,
      name: /your session: 1 route$/i,
    });
    expect(screen.getAllByText('Simplified recommendation')).toHaveLength(2);
    expect(
      screen.getByText(/full ranking step wasn't available/i),
    ).toBeVisible();
  });

  it('403 CONSENT_REQUIRED links to the consent step', async () => {
    const user = userEvent.setup();
    server.use(
      http.post('*/v1/me/sessions/recommendations', () =>
        HttpResponse.json(
          {
            code: 'CONSENT_REQUIRED',
            message: 'Active consent is required',
            request_id: 'req-403',
            field_errors: [],
          },
          { status: 403 },
        ),
      ),
    );
    signIn();
    renderApp('/session');
    await screen.findByRole('form', { name: 'Session setup' });
    await user.click(screen.getByRole('button', { name: 'Build my session' }));
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(/sessions need your consent/i);
    expect(
      within(alert).getByRole('link', { name: 'Turn on logging' }),
    ).toHaveAttribute('href', '/onboarding/consent');
  });

  it('503 shows the request id, keeps the form and retries manually', async () => {
    const user = userEvent.setup();
    const requests = captureRecommendationRequests();
    server.use(
      http.post(
        '*/v1/me/sessions/recommendations',
        () =>
          HttpResponse.json(
            {
              code: 'SERVICE_UNAVAILABLE',
              message: 'ranking unavailable',
              request_id: 'req-503',
              field_errors: [],
            },
            { status: 503, headers: { 'X-Request-Id': 'req-503' } },
          ),
        { once: true },
      ),
    );
    signIn();
    renderApp('/session');
    await screen.findByRole('form', { name: 'Session setup' });
    await user.click(screen.getByRole('button', { name: 'Build my session' }));
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent("We couldn't build your session.");
    expect(alert).toHaveTextContent('Request id req-503');
    expect(requests).toHaveLength(1);
    await user.click(within(alert).getByRole('button', { name: 'Try again' }));
    await screen.findByRole('heading', {
      level: 2,
      name: /your session: 4 routes/i,
    });
    expect(requests).toHaveLength(2);
  });

  it('without preferences it links to onboarding instead of building', async () => {
    signIn();
    server.use(
      http.get('*/v1/me', () =>
        HttpResponse.json(
          { ...meFixture.body, preferences: null },
          { status: 200 },
        ),
      ),
    );
    renderApp('/session');
    expect(
      await screen.findByText(/your board isn't set up yet/i),
    ).toBeVisible();
    expect(screen.getByRole('link', { name: 'Finish setup' })).toHaveAttribute(
      'href',
      '/onboarding',
    );
    expect(
      screen.queryByRole('form', { name: 'Session setup' }),
    ).not.toBeInTheDocument();
  });
});
