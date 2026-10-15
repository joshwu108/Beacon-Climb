import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { renderApp } from '../test/render';

// /explore, /session, /attempts and /decisions are real screens (BC-0115 to BC-0118)
// with their own tests; here only the routing shell is covered.
const PROTECTED_ROUTES = [
  '/session',
  '/attempts',
  '/attempts/new?route_variant_id=synthetic-route-001%3A35',
  '/decisions/018f0000-0000-7000-8000-000000000003',
] as const;

describe('routes', () => {
  it.each(PROTECTED_ROUTES)(
    '%s redirects to onboarding without a session',
    async (path) => {
      renderApp(path);
      expect(
        await screen.findByRole('heading', {
          level: 1,
          name: 'Set up Beacon Climb',
        }),
      ).toBeVisible();
    },
  );

  it('/ redirects to /onboarding', async () => {
    renderApp('/');
    expect(
      await screen.findByRole('heading', {
        level: 1,
        name: 'Set up Beacon Climb',
      }),
    ).toBeVisible();
  });

  it('unknown paths render the not-found page', async () => {
    renderApp('/nowhere');
    expect(await screen.findByRole('heading', { level: 1 })).toHaveTextContent(
      /isn't available/,
    );
  });
});
