import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { expectNoBlockingViolations } from '../test/axe';
import { renderApp } from '../test/render';

const PLACEHOLDER_ROUTES = [
  { path: '/explore', h1: 'Explore routes' },
  { path: '/session', h1: 'Build a session' },
  { path: '/attempts', h1: 'Log attempts' },
  {
    path: '/decisions/018f0000-0000-7000-8000-000000000003',
    h1: 'Why this route?',
  },
] as const;

describe('placeholder routes', () => {
  it.each(PLACEHOLDER_ROUTES)(
    '$path renders h1 "$h1" and is axe clean',
    async ({ path, h1 }) => {
      const { container } = renderApp(path);
      expect(
        await screen.findByRole('heading', { level: 1, name: h1 }),
      ).toBeVisible();
      expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
      await expectNoBlockingViolations(container);
    },
  );

  it('/decisions/:decisionId prints the id from the URL', async () => {
    renderApp('/decisions/018f0000-0000-7000-8000-000000000003');
    await screen.findByRole('heading', { level: 1 });
    expect(
      screen.getByText('018f0000-0000-7000-8000-000000000003'),
    ).toBeInTheDocument();
  });

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
