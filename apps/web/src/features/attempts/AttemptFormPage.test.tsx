import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import createAttempt409 from '../../../../../contracts/examples/createAttempt/response-409.json';
import createAttempt422Future from '../../../../../contracts/examples/createAttempt/response-422-future.json';
import meFixture from '../../../../../contracts/examples/getMe/response.json';
import { server } from '../../mocks/server';
import { expectNoBlockingViolations } from '../../test/axe';
import { renderApp } from '../../test/render';
import { tokenStore } from '../auth/token-store';
import { draftStore } from './draft-store';

const FUTURE = '2999-01-01T00:00:00Z';
const ROUTE = 'synthetic-route-002:35';
const PATH = `/attempts/new?route_variant_id=${encodeURIComponent(ROUTE)}`;

const signIn = (): void =>
  tokenStore.set({ token: 'opaque-demo-token', expiresAt: FUTURE });

interface Captured {
  readonly key: string | null;
  readonly body: Record<string, unknown>;
}

const captureAttemptPosts = (): Captured[] => {
  const captured: Captured[] = [];
  server.events.on('request:start', ({ request }) => {
    if (
      request.method === 'POST' &&
      new URL(request.url).pathname === '/v1/me/attempts'
    ) {
      void request
        .clone()
        .json()
        .then((body) =>
          captured.push({
            key: request.headers.get('Idempotency-Key'),
            body: body as Record<string, unknown>,
          }),
        );
    }
  });
  return captured;
};

const envelope = (status: number, body: Record<string, unknown>) =>
  HttpResponse.json(
    { request_id: 'req-test', field_errors: [], ...body },
    { status, headers: { 'X-Request-Id': 'req-test' } },
  );

const openForm = async () => {
  signIn();
  const rendered = renderApp(PATH);
  await screen.findByRole('form', { name: 'Log an attempt' });
  await screen.findByRole('heading', { level: 2, name: /synthetic/i });
  return rendered;
};

describe('AttemptFormPage', () => {
  beforeEach(() => server.events.removeAllListeners());
  afterEach(() => draftStore.clear());

  it('blocks a future time and a zero count before any request, then saves with one request', async () => {
    const user = userEvent.setup();
    const posts = captureAttemptPosts();
    const { container } = await openForm();
    await expectNoBlockingViolations(container);

    const when = screen.getByLabelText('When');
    await user.clear(when);
    await user.type(when, '2099-01-01T10:00');
    await user.click(screen.getByRole('button', { name: 'One attempt fewer' }));
    expect(
      screen.getByRole('button', { name: 'One attempt fewer' }),
    ).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Save attempt' }));
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(/that time is in the future/i);
    expect(posts).toHaveLength(0);

    await user.clear(when);
    await user.type(when, '2026-10-14T14:00');
    await user.click(screen.getByRole('button', { name: 'One attempt more' }));
    await user.click(screen.getByRole('switch', { name: /sent/i }));
    await user.type(screen.getByLabelText(/notes/i), 'Good go.');
    expect(screen.getByText(/8\/500 characters/)).toBeVisible();
    await user.click(screen.getByRole('button', { name: 'Save attempt' }));

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Attempt log' }),
    ).toBeVisible();
    expect(await screen.findByText('Saved as revision 1.')).toBeVisible();
    expect(posts).toHaveLength(1);
    expect(posts[0]!.body).toMatchObject({
      route_variant_id: ROUTE,
      attempts_count: 2,
      sent: true,
      notes: 'Good go.',
    });
    expect(posts[0]!.key).toMatch(/^[0-9a-f-]{36}$/);
    const history = screen.getByRole('region', { name: 'History' });
    expect(within(history).getAllByRole('listitem')).toHaveLength(2);
    expect(within(history).getByText('Good go.')).toBeVisible();
    // The draft left the device once the server list showed the attempt.
    await waitFor(() => expect(draftStore.list()).toEqual([]));
  });

  it('a double click sends exactly one request', async () => {
    const user = userEvent.setup();
    const posts = captureAttemptPosts();
    await openForm();
    await user.dblClick(screen.getByRole('button', { name: 'Save attempt' }));
    await screen.findByRole('heading', { level: 1, name: 'Attempt log' });
    await waitFor(() => expect(posts).toHaveLength(1));
    const history = await screen.findByRole('region', { name: 'History' });
    await waitFor(() =>
      expect(within(history).getAllByRole('listitem')).toHaveLength(2),
    );
    expect(posts).toHaveLength(1);
  });

  it('a network failure keeps the draft and Retry reuses the same Idempotency-Key', async () => {
    const user = userEvent.setup();
    const posts = captureAttemptPosts();
    server.use(
      http.post('*/v1/me/attempts', () => HttpResponse.error(), { once: true }),
    );
    await openForm();
    await user.click(screen.getByRole('button', { name: 'Save attempt' }));
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(/we didn't get a reply/i);
    expect(draftStore.list()[0]?.state).toBe('failed');
    await user.click(within(alert).getByRole('button', { name: 'Retry' }));
    await screen.findByRole('heading', { level: 1, name: 'Attempt log' });
    await waitFor(() => expect(posts).toHaveLength(2));
    expect(posts[0]!.key).toBe(posts[1]!.key);
    expect(posts[0]!.body).toEqual(posts[1]!.body);
  });

  it('a replayed 200 counts as saved', async () => {
    const user = userEvent.setup();
    const posts = captureAttemptPosts();
    // Make the first reply vanish after the server stored it: the retry is a 200 replay.
    let dropped = false;
    server.use(
      http.post('*/v1/me/attempts', async () => {
        if (dropped) return undefined;
        dropped = true;
        // Let the real handler record the key first, then fail the reply.
        await fetch('http://api.test/health/live');
        return HttpResponse.error();
      }),
    );
    await openForm();
    await user.click(screen.getByRole('button', { name: 'Save attempt' }));
    await screen.findByRole('alert');
    await user.click(screen.getByRole('button', { name: 'Retry' }));
    await screen.findByRole('heading', { level: 1, name: 'Attempt log' });
    expect(posts).toHaveLength(2);
  });

  it('422 from the server lands on the field', async () => {
    const user = userEvent.setup();
    server.use(
      http.post('*/v1/me/attempts', () =>
        HttpResponse.json(createAttempt422Future.body, { status: 422 }),
      ),
    );
    await openForm();
    await user.click(screen.getByRole('button', { name: 'Save attempt' }));
    const alerts = await screen.findAllByRole('alert');
    expect(
      alerts.some((a) =>
        /that time is in the future/i.test(a.textContent ?? ''),
      ),
    ).toBe(true);
    expect(screen.getByLabelText('When')).toHaveAttribute(
      'aria-invalid',
      'true',
    );
  });

  it('403 CONSENT_REQUIRED links to the consent step and keeps the draft', async () => {
    const user = userEvent.setup();
    server.use(
      http.post('*/v1/me/attempts', () =>
        envelope(403, { code: 'CONSENT_REQUIRED', message: 'consent' }),
      ),
    );
    await openForm();
    await user.click(screen.getByRole('button', { name: 'Save attempt' }));
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(/logging is off/i);
    expect(
      within(alert).getByRole('link', { name: 'Turn on logging' }),
    ).toHaveAttribute('href', '/onboarding/consent');
    expect(draftStore.list()).toHaveLength(1);
  });

  it('shows the consent gate when the profile has no active consent', async () => {
    signIn();
    server.use(
      http.get('*/v1/me', () =>
        HttpResponse.json(
          { ...meFixture.body, consent: null },
          { status: 200 },
        ),
      ),
    );
    renderApp(PATH);
    expect(await screen.findByText('Logging is off.')).toBeVisible();
    await waitFor(() =>
      expect(
        screen.getByRole('button', { name: 'Save attempt' }),
      ).toBeDisabled(),
    );
  });

  it('409 IDEMPOTENCY_CONFLICT explains and offers a new draft with a new key', async () => {
    const user = userEvent.setup();
    const posts = captureAttemptPosts();
    server.use(
      http.post(
        '*/v1/me/attempts',
        () => HttpResponse.json(createAttempt409.body, { status: 409 }),
        { once: true },
      ),
    );
    await openForm();
    await user.click(screen.getByRole('button', { name: 'Save attempt' }));
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(/already saved with different details/i);
    await user.click(
      within(alert).getByRole('button', { name: 'Create new draft' }),
    );
    await user.click(screen.getByRole('button', { name: 'Save attempt' }));
    await screen.findByRole('heading', { level: 1, name: 'Attempt log' });
    await waitFor(() => expect(posts).toHaveLength(2));
    expect(posts[1]!.key).not.toBe(posts[0]!.key);
  });

  it('asks for a route when none is given', async () => {
    signIn();
    renderApp('/attempts/new');
    expect(await screen.findByText(/pick the route first/i)).toBeVisible();
    expect(
      within(screen.getByRole('main')).getByRole('link', { name: 'Explore' }),
    ).toHaveAttribute('href', '/explore');
  });
});
