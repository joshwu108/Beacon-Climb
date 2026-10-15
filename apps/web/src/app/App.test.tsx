import { screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import consentResponse from '../../../../contracts/examples/getConsentDocument/response.json';
import { server } from '../mocks/server';
import { expectNoBlockingViolations } from '../test/axe';
import { renderApp } from '../test/render';

describe('App shell at /onboarding', () => {
  it('renders the onboarding step with the mocked consent text', async () => {
    renderApp('/onboarding');
    expect(
      await screen.findByRole('heading', {
        level: 1,
        name: 'Set up Beacon Climb',
      }),
    ).toBeVisible();
    expect(
      screen.getByRole('form', { name: /choose a username/i }),
    ).toBeVisible();
    expect(screen.getByLabelText('Username')).toHaveAttribute(
      'autocomplete',
      'username',
    );
    // Round trip: MSW serves contracts/examples/getConsentDocument/response.json.
    const consent = await screen.findByRole('region', {
      name: "What you're agreeing to",
    });
    expect(
      await within(consent).findByText('Beacon Climb consent v1'),
    ).toBeVisible();
    expect(
      within(consent).getByText(
        /research use of your pseudonymous history is optional/i,
      ),
    ).toBeVisible();
    expect(
      within(consent).getByText(`Consent ${consentResponse.body.version}`),
    ).toBeVisible();
  });

  it('has landmarks, a skip link and a progress nav with the account step current', async () => {
    renderApp('/onboarding');
    await screen.findByRole('heading', { level: 1 });
    expect(screen.getByRole('banner')).toBeVisible();
    expect(
      screen.getByRole('navigation', { name: 'Main navigation' }),
    ).toBeVisible();
    expect(screen.getByRole('main')).toHaveAttribute('id', 'main-content');
    expect(screen.getByRole('contentinfo')).toBeVisible();
    expect(
      screen.getByRole('link', { name: 'Skip to main content' }),
    ).toHaveAttribute('href', '#main-content');
    const progress = screen.getByRole('navigation', { name: 'Setup progress' });
    const current = within(progress)
      .getAllByRole('listitem')
      .find((li) => li.getAttribute('aria-current') === 'step');
    expect(current).toHaveTextContent('Account');
  });

  it('shows an error panel with the request id when the consent document fails', async () => {
    server.use(
      http.get('*/v1/consent/current', () =>
        HttpResponse.json(
          {
            code: 'INTERNAL_ERROR',
            message: 'boom',
            request_id: 'req-500',
            field_errors: [],
          },
          { status: 500, headers: { 'X-Request-Id': 'req-500' } },
        ),
      ),
    );
    renderApp('/onboarding');
    expect(await screen.findByRole('alert')).toHaveTextContent(
      "The consent text couldn't be loaded.",
    );
    expect(screen.getByText('Request id req-500')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Try again' })).toBeVisible();
  });

  it('has no serious or critical axe violations once the consent text has loaded', async () => {
    const { container } = renderApp('/onboarding');
    await screen.findByText('Beacon Climb consent v1');
    await expectNoBlockingViolations(container);
  });
});
