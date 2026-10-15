import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import consentFixture from '../../../../../contracts/examples/getConsentDocument/response.json';
import meFixture from '../../../../../contracts/examples/getMe/response.json';
import { server } from '../../mocks/server';
import { expectNoBlockingViolations } from '../../test/axe';
import { renderApp } from '../../test/render';
import { SESSION_STORAGE_KEY, tokenStore } from '../auth/token-store';

const FUTURE = '2999-01-01T00:00:00Z';

const stepHeading = (name: RegExp) =>
  screen.findByRole('heading', { level: 2, name });

const currentStepLabel = (): string | undefined =>
  within(screen.getByRole('navigation', { name: 'Setup progress' }))
    .getAllByRole('listitem')
    .find((li) => li.getAttribute('aria-current') === 'step')?.textContent;

const envelope = (
  status: number,
  body: Record<string, unknown>,
  headers: Record<string, string> = {},
) =>
  HttpResponse.json(
    { request_id: 'req-test', field_errors: [], ...body },
    { status, headers: { 'X-Request-Id': 'req-test', ...headers } },
  );

describe('onboarding flow', () => {
  it('walks account -> consent -> board -> goals and lands on /explore, axe clean on each step', async () => {
    const user = userEvent.setup();
    const { container } = renderApp('/onboarding');

    // Step 1: account
    await stepHeading(/choose a username/i);
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
    expect(currentStepLabel()).toContain('Account');
    await expectNoBlockingViolations(container);
    await user.type(screen.getByLabelText('Username'), 'newclimber');
    await user.type(screen.getByLabelText('Password'), 'a-long-password');
    await user.click(screen.getByRole('button', { name: 'Create account' }));

    // Token stored (memory + sessionStorage) and the profile fetched.
    await waitFor(() => expect(tokenStore.get()).toBe('opaque-demo-token'));
    expect(sessionStorage.getItem(SESSION_STORAGE_KEY)).toContain(
      'opaque-demo-token',
    );

    // Step 2: consent. Checkbox is unchecked and gates the button.
    await stepHeading(/decide what beacon may keep/i);
    expect(currentStepLabel()).toContain('Consent');
    expect(await screen.findByText('Beacon Climb consent v1')).toBeVisible();
    expect(
      screen.getByText(`Consent ${consentFixture.body.version}`),
    ).toBeVisible();
    const service = screen.getByRole('checkbox', {
      name: /i agree to the service use/i,
    });
    const research = screen.getByRole('checkbox', { name: /research use/i });
    expect(service).not.toBeChecked();
    expect(research).not.toBeChecked();
    const record = screen.getByRole('button', { name: /record my choice/i });
    expect(record).toBeDisabled();
    await expectNoBlockingViolations(container);
    await user.click(service);
    expect(record).toBeEnabled();
    await user.click(record);

    // Step 3: board and angle (single fixture board is preselected; angle must be chosen).
    await stepHeading(/which board do you climb on/i);
    expect(currentStepLabel()).toContain('Board and angle');
    await screen.findByRole('radio', { name: /synthetic/i });
    expect(screen.getByRole('radio', { name: /synthetic/i })).toBeChecked();
    expect(
      screen
        .getAllByRole('radio', { name: /°/ })
        .map((r) => r.getAttribute('value')),
    ).toEqual(['20', '35', '45']);
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Choose an angle.',
    );
    await expectNoBlockingViolations(container);
    await user.click(screen.getByRole('radio', { name: '45°' }));
    await user.click(screen.getByRole('button', { name: 'Continue' }));

    // Step 4: level and goals.
    await stepHeading(/where to start/i);
    expect(currentStepLabel()).toContain('Level and goals');
    await expectNoBlockingViolations(container);
    await user.click(screen.getByRole('radio', { name: /intermediate/i }));
    await user.click(screen.getByRole('checkbox', { name: /technique/i }));
    await user.click(screen.getByRole('checkbox', { name: /project/i }));
    await user.click(
      screen.getByRole('button', { name: /save and start exploring/i }),
    );

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Explore routes' }),
    ).toBeVisible();
  });

  it('409 USERNAME_TAKEN offers "sign in instead" which switches the same form to createSession', async () => {
    const user = userEvent.setup();
    // Register the name once directly against the mocks so the UI attempt is a repeat.
    await fetch('http://api.test/v1/auth/register', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: 'demo', password: 'demo-password' }),
    });

    renderApp('/onboarding');
    const form = await screen.findByRole('form', {
      name: /choose a username/i,
    });
    await user.type(within(form).getByLabelText('Username'), 'demo');
    await user.type(within(form).getByLabelText('Password'), 'demo-password');
    await user.click(
      within(form).getByRole('button', { name: 'Create account' }),
    );

    const alert = await within(form).findByRole('alert');
    expect(alert).toHaveTextContent('That username is already registered.');
    expect(within(form).getByLabelText('Username')).toHaveAttribute(
      'aria-invalid',
      'true',
    );
    expect(within(form).getByLabelText('Username')).toHaveAttribute(
      'aria-describedby',
      alert.id,
    );
    await user.click(
      within(alert).getByRole('button', { name: 'Sign in instead' }),
    );
    expect(within(form).getByLabelText('Password')).toHaveFocus();
    expect(within(form).getByLabelText('Password')).toHaveAttribute(
      'autocomplete',
      'current-password',
    );
    await user.click(within(form).getByRole('button', { name: 'Sign in' }));
    // The seeded user already has consent and preferences: straight to Explore.
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Explore routes' }),
    ).toBeVisible();
  });

  it('maps 422 field_errors from register onto the username field', async () => {
    const user = userEvent.setup();
    server.use(
      http.post('*/v1/auth/register', () =>
        envelope(422, {
          code: 'VALIDATION_FAILED',
          message: 'Request failed validation',
          field_errors: [
            {
              field: 'username',
              code: 'pattern',
              message: 'must match ^[a-z0-9_]{3,32}$',
            },
          ],
        }),
      ),
    );
    renderApp('/onboarding');
    await stepHeading(/choose a username/i);
    await user.type(screen.getByLabelText('Username'), 'validname');
    await user.type(screen.getByLabelText('Password'), 'long-enough-pw');
    await user.click(screen.getByRole('button', { name: 'Create account' }));
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('must match ^[a-z0-9_]{3,32}$');
    expect(screen.getByLabelText('Username')).toHaveAttribute(
      'aria-describedby',
      alert.id,
    );
  });

  it('429 shows a form-level message with Retry-After and the request id', async () => {
    const user = userEvent.setup();
    server.use(
      http.post('*/v1/auth/register', () =>
        envelope(
          429,
          { code: 'RATE_LIMITED', message: 'Too many requests' },
          { 'Retry-After': '42' },
        ),
      ),
    );
    renderApp('/onboarding');
    await stepHeading(/choose a username/i);
    await user.type(screen.getByLabelText('Username'), 'someone');
    await user.type(screen.getByLabelText('Password'), 'long-enough-pw');
    await user.click(screen.getByRole('button', { name: 'Create account' }));
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Too many attempts.');
    expect(alert).toHaveTextContent('Try again in 42 seconds.');
    expect(alert).toHaveTextContent('Request id req-test');
  });

  it('shows a server UNSUPPORTED_ANGLE field error on the board step', async () => {
    const user = userEvent.setup();
    tokenStore.set({ token: 'opaque-demo-token', expiresAt: FUTURE });
    server.use(
      http.get('*/v1/me', () =>
        HttpResponse.json(
          { ...meFixture.body, preferences: null },
          { status: 200 },
        ),
      ),
      http.put('*/v1/me/preferences', () =>
        envelope(422, {
          code: 'UNSUPPORTED_ANGLE',
          message: 'Angle is not available on this board',
          field_errors: [
            {
              field: 'angle_deg',
              code: 'enum',
              message: 'must be one of 20, 35',
            },
          ],
        }),
      ),
    );
    renderApp('/onboarding');
    // Consent exists, so the flow resumes at the board step.
    await stepHeading(/which board do you climb on/i);
    await user.click(await screen.findByRole('radio', { name: '45°' }));
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    await stepHeading(/where to start/i);
    await user.click(screen.getByRole('radio', { name: /novice/i }));
    await user.click(
      screen.getByRole('button', { name: /save and start exploring/i }),
    );

    // Back on the board step with the field error attached to the angle group.
    await stepHeading(/which board do you climb on/i);
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('must be one of 20, 35');
    const angleGroup = screen.getByRole('group', { name: /wall angle/i });
    expect(angleGroup).toHaveAttribute('aria-describedby', alert.id);
    expect(screen.getByRole('radio', { name: '45°' })).toHaveAttribute(
      'aria-invalid',
      'true',
    );
    // Choosing another angle clears it.
    await user.click(screen.getByRole('radio', { name: '35°' }));
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('resumes: a token with complete preferences goes straight to /explore', async () => {
    tokenStore.set({ token: 'opaque-demo-token', expiresAt: FUTURE });
    renderApp('/onboarding');
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Explore routes' }),
    ).toBeVisible();
  });

  it('resumes: a token with no consent lands on the consent step even when the URL says account', async () => {
    tokenStore.set({ token: 'opaque-demo-token', expiresAt: FUTURE });
    server.use(
      http.get('*/v1/me', () =>
        HttpResponse.json(
          { ...meFixture.body, consent: null, preferences: null },
          { status: 200 },
        ),
      ),
    );
    renderApp('/onboarding/account');
    await stepHeading(/decide what beacon may keep/i);
    expect(currentStepLabel()).toContain('Consent');
  });

  it('a dead token (401 on /v1/me) falls back to the account step', async () => {
    tokenStore.set({ token: 'stale', expiresAt: FUTURE });
    server.use(
      http.get('*/v1/me', () =>
        envelope(401, {
          code: 'UNAUTHENTICATED',
          message: 'Authentication required',
        }),
      ),
    );
    renderApp('/onboarding');
    await stepHeading(/choose a username/i);
    expect(tokenStore.get()).toBeNull();
  });

  it('consent step shows the envelope error with request id when recording fails', async () => {
    const user = userEvent.setup();
    tokenStore.set({ token: 'opaque-demo-token', expiresAt: FUTURE });
    server.use(
      http.get('*/v1/me', () =>
        HttpResponse.json(
          { ...meFixture.body, consent: null, preferences: null },
          { status: 200 },
        ),
      ),
      http.post('*/v1/me/consent', () =>
        envelope(500, { code: 'INTERNAL_ERROR', message: 'boom' }),
      ),
    );
    renderApp('/onboarding');
    await stepHeading(/decide what beacon may keep/i);
    await user.click(
      await screen.findByRole('checkbox', {
        name: /i agree to the service use/i,
      }),
    );
    await user.click(screen.getByRole('button', { name: /record my choice/i }));
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Something went wrong on our side.');
    expect(alert).toHaveTextContent('Request id req-test');
  });
});
