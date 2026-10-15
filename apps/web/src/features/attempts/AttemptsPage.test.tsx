import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import listFixture from '../../../../../contracts/examples/listAttempts/response.json';
import { server } from '../../mocks/server';
import { expectNoBlockingViolations } from '../../test/axe';
import { renderApp } from '../../test/render';
import { tokenStore } from '../auth/token-store';
import { draftStore, type AttemptDraft } from './draft-store';

const FUTURE = '2999-01-01T00:00:00Z';
const FIXTURE_ID = listFixture.body.items[0]!.attempt_id;
const AUTH = {
  Authorization: 'Bearer opaque-demo-token',
  'Content-Type': 'application/json',
};
const signIn = (): void =>
  tokenStore.set({ token: 'opaque-demo-token', expiresAt: FUTURE });

interface Captured {
  readonly method: string;
  readonly path: string;
  readonly ifMatch: string | null;
  readonly key: string | null;
}

const captureRequests = (): Captured[] => {
  const captured: Captured[] = [];
  server.events.on('request:start', ({ request }) => {
    const url = new URL(request.url);
    // The app client always sends X-Request-Id; the tests' own fetches do not.
    if (
      url.pathname.startsWith('/v1/me/attempts') &&
      request.headers.has('X-Request-Id')
    ) {
      captured.push({
        method: request.method,
        path: url.pathname,
        ifMatch: request.headers.get('If-Match'),
        key: request.headers.get('Idempotency-Key'),
      });
    }
  });
  return captured;
};

const historyRows = () =>
  within(screen.getByRole('region', { name: 'History' })).getAllByRole(
    'listitem',
  );

const fixtureRow = async () => {
  await screen.findByText('1 attempt loaded');
  return historyRows().find((row) =>
    within(row).queryByText('Synthetic Traverse 1'),
  )!;
};

/** Another client corrects the fixture attempt directly against the mocks. */
const bumpElsewhere = async (): Promise<void> => {
  const res = await fetch(`http://api.test/v1/me/attempts/${FIXTURE_ID}`, {
    method: 'PATCH',
    headers: { ...AUTH, 'If-Match': '"1"' },
    body: JSON.stringify({ perceived_effort: 9 }),
  });
  expect(res.status).toBe(200);
};

const failedDraft = (): AttemptDraft => ({
  draft_id: 'draft-1',
  idempotency_key: 'key-draft-1',
  body: {
    route_variant_id: 'synthetic-route-003:35',
    occurred_at: '2026-10-14T15:00:00Z',
    attempts_count: 3,
    sent: false,
  },
  state: 'failed',
  attempt: null,
  error: {
    status: 0,
    code: 'UNKNOWN',
    message: 'Network request failed',
    request_id: null,
    field_errors: [],
  },
  created_at: '2026-10-14T15:00:10Z',
  route_name: 'Synthetic Sloper Arc 3',
});

describe('AttemptsPage', () => {
  beforeEach(() => server.events.removeAllListeners());
  afterEach(() => {
    delete (navigator as unknown as Record<string, unknown>).onLine;
    return draftStore.clear();
  });

  it('lists the history newest first with revision, status and notes, and is axe clean', async () => {
    signIn();
    const { container } = renderApp('/attempts');
    const row = await fixtureRow();
    expect(within(row).getByText('2 attempts')).toBeVisible();
    expect(within(row).getByText('Sent')).toBeVisible();
    expect(within(row).getByText('Revision 1')).toBeVisible();
    expect(within(row).queryByText('Retracted')).not.toBeInTheDocument();
    expect(screen.getByText('End of your history.')).toBeVisible();
    await expectNoBlockingViolations(container);
  });

  it('Correct sends a PATCH with If-Match "<revision>" and only the changed fields', async () => {
    const user = userEvent.setup();
    const requests = captureRequests();
    signIn();
    renderApp('/attempts');
    const row = await fixtureRow();
    await user.click(within(row).getByRole('button', { name: 'Correct' }));
    const form = within(row).getByRole('form', {
      name: /correct synthetic traverse 1/i,
    });
    expect(within(form).queryByLabelText('When')).not.toBeInTheDocument();
    await user.click(
      within(form).getByRole('button', { name: 'One attempt more' }),
    );
    await user.type(
      within(form).getByLabelText(/notes/i),
      'Third go stuck the crux.',
    );
    await user.click(
      within(form).getByRole('button', { name: 'Save correction' }),
    );
    await waitFor(() =>
      expect(within(row).getByText('Revision 2')).toBeVisible(),
    );
    expect(within(row).getByText('Corrected')).toBeVisible();
    expect(within(row).getByText('3 attempts')).toBeVisible();
    expect(within(row).getByText('Third go stuck the crux.')).toBeVisible();
    const patch = requests.find((r) => r.method === 'PATCH');
    expect(patch).toMatchObject({
      path: `/v1/me/attempts/${FIXTURE_ID}`,
      ifMatch: '"1"',
    });
  });

  it('a REVISION_CONFLICT shows the current revision, Refresh re-reads and the retry succeeds', async () => {
    const user = userEvent.setup();
    const requests = captureRequests();
    signIn();
    renderApp('/attempts');
    const row = await fixtureRow();
    await bumpElsewhere();
    await user.click(within(row).getByRole('button', { name: 'Correct' }));
    const form = within(row).getByRole('form', { name: /correct/i });
    await user.click(
      within(form).getByRole('button', { name: 'One attempt more' }),
    );
    await user.click(
      within(form).getByRole('button', { name: 'Save correction' }),
    );
    const alert = await within(row).findByRole('alert');
    expect(alert).toHaveTextContent(
      'This attempt changed elsewhere (revision 2). Refresh',
    );
    await user.click(within(alert).getByRole('button', { name: 'Refresh' }));
    await waitFor(() =>
      expect(within(row).getByText('Revision 2')).toBeVisible(),
    );
    expect(within(row).getByText('Effort 9/10')).toBeVisible();
    expect(
      requests.some((r) => r.method === 'GET' && r.path.endsWith(FIXTURE_ID)),
    ).toBe(true);
    // The refreshed form keeps editing the latest version; re-apply the change.
    const reopened = within(row).getByRole('form', { name: /correct/i });
    await user.click(
      within(reopened).getByRole('button', { name: 'One attempt more' }),
    );
    await user.click(
      within(reopened).getByRole('button', { name: 'Save correction' }),
    );
    await waitFor(() =>
      expect(within(row).getByText('Revision 3')).toBeVisible(),
    );
    const patches = requests
      .filter((r) => r.method === 'PATCH')
      .map((r) => r.ifMatch);
    expect(patches).toEqual(['"1"', '"2"']);
  });

  it('Undo (retract) confirms, PATCHes {retract: true} and marks the row Retracted', async () => {
    const user = userEvent.setup();
    const requests = captureRequests();
    signIn();
    renderApp('/attempts');
    const row = await fixtureRow();
    await user.click(
      within(row).getByRole('button', { name: 'Undo (retract)' }),
    );
    expect(within(row).getByText(/retract this attempt\?/i)).toBeVisible();
    await user.click(within(row).getByRole('button', { name: 'Keep attempt' }));
    expect(requests.filter((r) => r.method === 'PATCH')).toHaveLength(0);
    await user.click(
      within(row).getByRole('button', { name: 'Undo (retract)' }),
    );
    await user.click(within(row).getByRole('button', { name: 'Retract' }));
    await waitFor(() =>
      expect(within(row).getByText('Retracted')).toBeVisible(),
    );
    expect(within(row).getByText('Revision 2')).toBeVisible();
    expect(
      within(row).queryByRole('button', { name: 'Correct' }),
    ).not.toBeInTheDocument();
    expect(requests.filter((r) => r.method === 'PATCH')).toMatchObject([
      { ifMatch: '"1"' },
    ]);
  });

  it('shows unsynced drafts on top and the online event syncs them once with the same key', async () => {
    const requests = captureRequests();
    await draftStore.put(failedDraft());
    // The browser is offline when the page opens, so nothing is sent on mount.
    Object.defineProperty(navigator, 'onLine', {
      configurable: true,
      get: () => false,
    });
    signIn();
    renderApp('/attempts');
    // Offline, the mocks answer every request with a network error, the list included.
    expect(await screen.findByRole('alert')).toHaveTextContent(
      "Your attempts couldn't be loaded.",
    );
    const drafts = screen.getByRole('region', {
      name: /waiting to save \(1\)/i,
    });
    expect(within(drafts).getByText('Synthetic Sloper Arc 3')).toBeVisible();
    expect(within(drafts).getByText('Waiting to save')).toBeVisible();
    expect(within(drafts).getByRole('button', { name: 'Retry' })).toBeVisible();
    expect(requests.filter((r) => r.method === 'POST')).toHaveLength(0);

    delete (navigator as unknown as Record<string, unknown>).onLine;
    window.dispatchEvent(new Event('online'));
    await screen.findByText('2 attempts loaded');
    expect(
      screen.queryByRole('region', { name: /waiting to save/i }),
    ).not.toBeInTheDocument();
    const posts = requests.filter((r) => r.method === 'POST');
    expect(posts).toHaveLength(1);
    expect(posts[0]!.key).toBe('key-draft-1');

    window.dispatchEvent(new Event('online'));
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(requests.filter((r) => r.method === 'POST')).toHaveLength(1);
    await waitFor(() => expect(draftStore.list()).toEqual([]));
  });

  it('a failed draft left from an earlier visit is retried once on mount when online', async () => {
    const requests = captureRequests();
    await draftStore.put(failedDraft());
    signIn();
    renderApp('/attempts');
    await screen.findByText('2 attempts loaded');
    expect(requests.filter((r) => r.method === 'POST')).toHaveLength(1);
  });

  it('Discard removes a draft that needs attention', async () => {
    const user = userEvent.setup();
    await draftStore.put({
      ...failedDraft(),
      error: {
        status: 409,
        code: 'IDEMPOTENCY_CONFLICT',
        message: 'conflict',
        request_id: 'r',
        field_errors: [],
      },
    });
    signIn();
    renderApp('/attempts');
    const drafts = await screen.findByRole('region', {
      name: /waiting to save/i,
    });
    expect(within(drafts).getByText('Needs attention')).toBeVisible();
    expect(
      within(drafts).queryByRole('button', { name: 'Retry' }),
    ).not.toBeInTheDocument();
    await user.click(
      within(drafts).getByRole('button', { name: 'Discard draft' }),
    );
    await waitFor(() =>
      expect(
        screen.queryByRole('region', { name: /waiting to save/i }),
      ).not.toBeInTheDocument(),
    );
  });

  it('Load more follows next_cursor', async () => {
    const user = userEvent.setup();
    for (let i = 0; i < 21; i += 1) {
      await fetch('http://api.test/v1/me/attempts', {
        method: 'POST',
        headers: { ...AUTH, 'Idempotency-Key': `seed-${i}` },
        body: JSON.stringify({
          route_variant_id: 'synthetic-route-002:35',
          occurred_at: `2026-10-13T${String(i).padStart(2, '0')}:00:00Z`,
          attempts_count: 1,
          sent: false,
        }),
      });
    }
    signIn();
    renderApp('/attempts');
    expect(
      await screen.findByText('20 attempts loaded, more available'),
    ).toBeVisible();
    await user.click(screen.getByRole('button', { name: 'Load more' }));
    expect(await screen.findByText('22 attempts loaded')).toBeVisible();
    expect(historyRows()).toHaveLength(22);
  });
});
