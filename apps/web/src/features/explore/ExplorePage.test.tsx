import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { beforeEach, describe, expect, it } from 'vitest';
import meFixture from '../../../../../contracts/examples/getMe/response.json';
import routeFixture from '../../../../../contracts/examples/getRoute/response.json';
import { server } from '../../mocks/server';
import { expectNoBlockingViolations } from '../../test/axe';
import { renderApp } from '../../test/render';
import { tokenStore } from '../auth/token-store';

const FUTURE = '2999-01-01T00:00:00Z';

const signIn = (): void =>
  tokenStore.set({ token: 'opaque-demo-token', expiresAt: FUTURE });

const capturedRouteRequests = (): URL[] => {
  const urls: URL[] = [];
  server.events.on('request:start', ({ request }) => {
    const url = new URL(request.url);
    if (url.pathname === '/v1/routes') urls.push(url);
  });
  return urls;
};

const routeRows = () =>
  within(screen.getByRole('region', { name: /routes at/i })).getAllByRole(
    'listitem',
  );

describe('ExplorePage', () => {
  beforeEach(() => server.events.removeAllListeners());

  it('redirects to onboarding without a session', async () => {
    renderApp('/explore');
    expect(
      await screen.findByRole('heading', {
        level: 1,
        name: 'Set up Beacon Climb',
      }),
    ).toBeVisible();
  });

  it('redirects to onboarding when preferences are missing', async () => {
    signIn();
    server.use(
      http.get('*/v1/me', () =>
        HttpResponse.json(
          { ...meFixture.body, preferences: null },
          { status: 200 },
        ),
      ),
    );
    renderApp('/explore');
    expect(
      await screen.findByRole('heading', {
        level: 1,
        name: 'Set up Beacon Climb',
      }),
    ).toBeVisible();
  });

  it('lists the first page for the preferred board and angle with grade, evidence and low-evidence badges', async () => {
    const requests = capturedRouteRequests();
    signIn();
    const { container } = renderApp('/explore');
    expect(
      await screen.findByText('10 routes loaded, more available'),
    ).toHaveAttribute('role', 'status');
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
    expect(
      screen.getByRole('heading', { level: 2, name: 'Routes at 35 degrees' }),
    ).toBeVisible();
    // Never an incompatible request: board and angle from preferences, page size 10.
    expect(requests).toHaveLength(1);
    expect(Object.fromEntries(requests[0]!.searchParams)).toEqual({
      board_id: 'synthetic-demo-1',
      angle_deg: '35',
      limit: '10',
    });
    const rows = routeRows();
    expect(rows).toHaveLength(10);
    const fixtureRow = rows.find((row) =>
      within(row).queryByText('Synthetic Traverse 1'),
    );
    expect(fixtureRow).toBeDefined();
    expect(within(fixtureRow!).getByText('17')).toBeVisible();
    expect(within(fixtureRow!).getByText('V2')).toBeVisible();
    expect(within(fixtureRow!).getByText('0 observations')).toBeVisible();
    expect(within(fixtureRow!).getByText('Low evidence')).toBeVisible();
    expect(within(fixtureRow!).getByRole('link')).toHaveAttribute(
      'href',
      '/explore/synthetic-route-001%3A35',
    );
    // Angle choices come from the board; the current one is checked.
    const search = screen.getByRole('search', { name: 'Route filters' });
    expect(within(search).getByRole('radio', { name: '35°' })).toBeChecked();
    await expectNoBlockingViolations(container);
  });

  it('Load more appends the next page using next_cursor and then reports the end', async () => {
    const user = userEvent.setup();
    const requests = capturedRouteRequests();
    signIn();
    renderApp('/explore');
    await screen.findByText('10 routes loaded, more available');
    await user.click(screen.getByRole('button', { name: 'Load more routes' }));
    expect(await screen.findByText('20 routes loaded')).toBeVisible();
    expect(routeRows()).toHaveLength(20);
    expect(requests[1]!.searchParams.get('cursor')).toMatch(/^[A-Za-z0-9_-]+$/);
    expect(
      screen.queryByRole('button', { name: 'Load more routes' }),
    ).not.toBeInTheDocument();
    expect(screen.getByText('End of the index for 35 degrees.')).toBeVisible();
    const ids = routeRows().map((row) =>
      within(row).getByRole('link').getAttribute('href'),
    );
    expect(new Set(ids).size).toBe(20);
  });

  it('applying filters writes them to the URL and the request, and Clear resets the grade range', async () => {
    const user = userEvent.setup();
    const requests = capturedRouteRequests();
    signIn();
    renderApp('/explore');
    await screen.findByText('10 routes loaded, more available');
    const search = screen.getByRole('search', { name: 'Route filters' });
    await user.click(within(search).getByRole('radio', { name: '45°' }));
    await user.type(within(search).getByLabelText('from'), '15');
    await user.type(within(search).getByLabelText('to'), '20');
    await user.click(
      within(search).getByRole('button', { name: 'Apply filters' }),
    );
    expect(
      await screen.findByRole('heading', {
        level: 2,
        name: 'Routes at 45 degrees',
      }),
    ).toBeVisible();
    await waitFor(() =>
      expect(
        Object.fromEntries(requests[requests.length - 1]!.searchParams),
      ).toEqual({
        board_id: 'synthetic-demo-1',
        angle_deg: '45',
        grade_min: '15',
        grade_max: '20',
        limit: '10',
      }),
    );
    await screen.findByText(/routes? loaded/);
    for (const row of routeRows()) {
      const grade = Number(
        within(row)
          .getByLabelText(/^Grade /)
          .textContent?.match(/\d+/)?.[0],
      );
      expect(grade).toBeGreaterThanOrEqual(15);
      expect(grade).toBeLessThanOrEqual(20);
    }
    await user.click(screen.getByRole('button', { name: 'Clear' }));
    await waitFor(() =>
      expect(requests[requests.length - 1]!.searchParams.has('grade_min')).toBe(
        false,
      ),
    );
    expect(
      screen.getByRole('heading', { level: 2, name: 'Routes at 45 degrees' }),
    ).toBeVisible();
  });

  it('blocks grade from > to before any request', async () => {
    const user = userEvent.setup();
    const requests = capturedRouteRequests();
    signIn();
    renderApp('/explore');
    await screen.findByText('10 routes loaded, more available');
    await user.type(screen.getByLabelText('from'), '30');
    await user.type(screen.getByLabelText('to'), '10');
    await user.click(screen.getByRole('button', { name: 'Apply filters' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Grade from must not be higher than grade to.',
    );
    expect(requests).toHaveLength(1);
  });

  it('shows the empty state when nothing matches', async () => {
    signIn();
    renderApp('/explore?grade_min=99&grade_max=100');
    expect(await screen.findByText('No routes match.')).toBeVisible();
    expect(
      screen.getByText(/no routes match these filters at 35 degrees/i),
    ).toBeVisible();
    expect(screen.getByRole('button', { name: 'Clear filters' })).toBeVisible();
  });

  it('shows the error state with the request id and retries', async () => {
    const user = userEvent.setup();
    let failures = 0;
    server.use(
      http.get('*/v1/routes', () => {
        if (failures === 0) {
          failures += 1;
          return HttpResponse.json(
            {
              code: 'INTERNAL_ERROR',
              message: 'boom',
              request_id: 'req-routes',
              field_errors: [],
            },
            { status: 500, headers: { 'X-Request-Id': 'req-routes' } },
          );
        }
        return undefined;
      }),
    );
    signIn();
    renderApp('/explore');
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent("Routes couldn't be loaded.");
    expect(alert).toHaveTextContent('Request id req-routes');
    await user.click(within(alert).getByRole('button', { name: 'Try again' }));
    expect(
      await screen.findByText('10 routes loaded, more available'),
    ).toBeVisible();
  });
});

describe('RouteDetailPage', () => {
  it('renders the board with placements, the hidden placement list, grade, evidence and the missing-tags notice', async () => {
    signIn();
    const { container } = renderApp(
      `/explore/${encodeURIComponent(routeFixture.body.route_variant_id)}`,
    );
    expect(
      await screen.findByRole('heading', {
        level: 1,
        name: 'Synthetic Traverse 1',
      }),
    ).toBeVisible();
    expect(
      screen.getByRole('link', { name: /back to the index/i }),
    ).toHaveAttribute('href', '/explore');
    const svg = await screen.findByRole('img', {
      name: /route synthetic traverse 1 uses 3/i,
    });
    const list = document.getElementById(
      svg.getAttribute('aria-describedby')!,
    )!;
    expect(
      within(list)
        .getAllByRole('listitem')
        .map((li) => li.textContent),
    ).toEqual([
      'Start hold h1 at 0.25, 0.50 (middle left)',
      'Hand hold h2 at 0.50, 0.75 (upper centre)',
      'Finish hold h3 at 0.75, 1 (upper right)',
    ]);
    const facts = screen.getByRole('complementary', { name: 'Route facts' });
    expect(within(facts).getByText('17')).toBeVisible();
    expect(within(facts).getByText('V2')).toBeVisible();
    expect(within(facts).getByText('0 observations')).toBeVisible();
    expect(within(facts).getByText('Low evidence')).toBeVisible();
    expect(
      within(facts).getByText('Style tags: not available for this route.'),
    ).toBeVisible();
    expect(screen.getByRole('group', { name: 'Hold roles' })).toBeVisible();
    await expectNoBlockingViolations(container);
  });

  it('shows tags when present', async () => {
    signIn();
    // Generated route with tags: seed 4 at 20 degrees is synthetic-route-004:20.
    renderApp('/explore/synthetic-route-004%3A20');
    await screen.findByRole('heading', { level: 1, name: /synthetic/i });
    const facts = screen.getByRole('complementary', { name: 'Route facts' });
    expect(within(facts).getByText('crimpy')).toBeVisible();
    expect(
      within(facts).queryByText(/not available for this route/),
    ).not.toBeInTheDocument();
  });

  it('404 explains the route is unavailable and keeps the back link', async () => {
    signIn();
    renderApp('/explore/gone%3A35');
    expect(
      await screen.findByRole('heading', {
        level: 1,
        name: "This route isn't available.",
      }),
    ).toBeVisible();
    expect(screen.getByText(/request id mock-not-found/i)).toBeVisible();
    expect(
      screen.getByRole('link', { name: /back to the index/i }),
    ).toBeVisible();
  });
});
