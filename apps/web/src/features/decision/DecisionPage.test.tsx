import { screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import decisionFixture from '../../../../../contracts/examples/getDecision/response.json';
import { expectNoBlockingViolations } from '../../test/axe';
import { renderApp } from '../../test/render';
import { tokenStore } from '../auth/token-store';

const FUTURE = '2999-01-01T00:00:00Z';
const AUTH = {
  Authorization: 'Bearer opaque-demo-token',
  'Content-Type': 'application/json',
};
const signIn = (): void =>
  tokenStore.set({ token: 'opaque-demo-token', expiresAt: FUTURE });

describe('DecisionPage', () => {
  it('shows request context, versions, flags and the ranked list with reason explanations', async () => {
    signIn();
    const { container } = renderApp(
      `/decisions/${decisionFixture.body.decision_id}`,
    );
    expect(
      await screen.findByRole('heading', {
        level: 1,
        name: 'Why these routes',
      }),
    ).toBeVisible();
    const about = screen.getByRole('complementary', {
      name: 'About this list',
    });
    expect(within(about).getByText('Technique, up to 4 routes')).toBeVisible();
    expect(within(about).getByText('synthetic-demo-1 at 40°')).toBeVisible();
    expect(within(about).getByText('heuristic-v1')).toBeVisible();
    expect(
      within(about).getByText('none (no learned model in use)'),
    ).toBeVisible();
    expect(within(about).getByText('cold start; full ranking')).toBeVisible();
    // BC-0310: the fixture decision was a synchronous recompute with an explicit cutoff.
    const features = within(about).getByText(/recomputed on request/);
    expect(features).toHaveTextContent(/^recomputed on request, cutoff /);
    expect(within(features).getByRole('time')).toHaveAttribute(
      'dateTime',
      decisionFixture.body.features_cutoff_w,
    );
    expect(
      screen.getByText(/recommendations are heuristic until you log/i),
    ).toBeVisible();
    const list = screen.getByRole('list', {
      name: 'Recommended routes in order',
    });
    const items = Array.from(list.querySelectorAll<HTMLElement>(':scope > li'));
    expect(items).toHaveLength(1);
    const first = items[0]!;
    expect(within(first).getByText('Position 1')).toBeVisible();
    expect(
      within(first).getByRole('heading', {
        level: 3,
        name: 'Synthetic Traverse 1',
      }),
    ).toBeVisible();
    expect(
      within(first).getByText('Matches your board and angle'),
    ).toBeVisible();
    expect(
      within(first).getByText(
        'This route exists on your board at your selected angle.',
      ),
    ).toBeVisible();
    expect(within(first).getByText('Grade fits your goal')).toBeVisible();
    expect(within(first).getByText('0.720')).toBeVisible();
    expect(
      within(first).getByText('No personalised estimate yet'),
    ).toBeVisible();
    expect(within(first).getByText('Not enough history')).toBeVisible();
    expect(
      within(first).getByRole('link', { name: 'Log an attempt' }),
    ).toHaveAttribute(
      'href',
      '/attempts/new?route_variant_id=synthetic-route-001%3A40',
    );
    await expectNoBlockingViolations(container);
  });

  it('serves the decision recorded by the last createRecommendations call', async () => {
    signIn();
    const res = await fetch('http://api.test/v1/me/sessions/recommendations', {
      method: 'POST',
      headers: AUTH,
      body: JSON.stringify({
        board_id: 'synthetic-demo-1',
        angle_deg: 20,
        goal: 'STRENGTH',
        max_routes: 5,
        session_id: '11111111-1111-7111-8111-111111111111',
        exclude_route_variant_ids: ['synthetic-route-001:20'],
      }),
    });
    const { decision_id } = (await res.json()) as { decision_id: string };
    renderApp(`/decisions/${decision_id}`);
    await screen.findByRole('heading', { level: 1, name: 'Why these routes' });
    const about = screen.getByRole('complementary', {
      name: 'About this list',
    });
    expect(within(about).getByText('Strength, up to 5 routes')).toBeVisible();
    expect(within(about).getByText('synthetic-demo-1 at 20°')).toBeVisible();
    expect(within(about).getByText('synthetic-route-001:20')).toBeVisible();
    expect(
      screen.getByRole('heading', {
        level: 2,
        name: 'All routes in this list (5)',
      }),
    ).toBeVisible();
  });

  it('404 explains the decision is missing or not yours, also for malformed ids', async () => {
    signIn();
    renderApp('/decisions/018f0000-0000-7000-8000-00000000dead');
    expect(
      await screen.findByRole('heading', {
        level: 1,
        name: "We couldn't find this decision.",
      }),
    ).toBeVisible();
    expect(screen.getByText(/request id mock-not-found/i)).toBeVisible();
  });

  it('treats a malformed id as not found without a request', async () => {
    signIn();
    renderApp('/decisions/not-a-uuid');
    expect(
      await screen.findByRole('heading', {
        level: 1,
        name: "We couldn't find this decision.",
      }),
    ).toBeVisible();
  });
});
