/**
 * Key screens through the real router (expo-router/testing-library) and the in-process
 * mocks: onboarding from account to consent, and the session builder with the debug policy
 * toggle. The root layout is swapped for TestLayout (same providers, no fonts or frame).
 */
import { resetMockState } from '@web/mocks/state';
import {
  fireEvent,
  renderRouter,
  screen,
  waitFor,
} from 'expo-router/testing-library';
import { tokenStore } from '../auth/token-store';
import { sessionStore } from '../features/session/session-store';
import TestLayout from '../test/TestLayout';
import TabsLayout from '../app/(tabs)/_layout';
import SessionTab from '../app/(tabs)/session';
import Index from '../app/index';
import Onboarding from '../app/onboarding';

const routes = {
  _layout: TestLayout,
  index: Index,
  onboarding: Onboarding,
  '(tabs)/_layout': TabsLayout,
  '(tabs)/session': SessionTab,
  '(tabs)/explore': () => null,
  '(tabs)/attempts': () => null,
  '(tabs)/scan': () => null,
};

const FUTURE = '2999-01-01T00:00:00Z';

beforeEach(() => {
  resetMockState();
  tokenStore.clear();
  sessionStore.reset();
});

describe('onboarding', () => {
  it('registers, stores the session token and moves on to consent', async () => {
    await renderRouter(routes, { initialUrl: '/onboarding' });
    expect(await screen.findByText('Choose a username')).toBeTruthy();

    await fireEvent.changeText(screen.getByTestId('username'), 'chalk_hands');
    await fireEvent.changeText(
      screen.getByTestId('password'),
      'long-enough-pw',
    );
    await fireEvent.press(screen.getByTestId('account-submit'));

    expect(await screen.findByText('Decide what Beacon may keep')).toBeTruthy();
    expect(tokenStore.get()).not.toBeNull();
    expect(screen.getByLabelText(/Step 2 of 4/)).toBeTruthy();
  });

  it('shows field errors before any request for an invalid username', async () => {
    await renderRouter(routes, { initialUrl: '/onboarding' });
    await fireEvent.changeText(
      await screen.findByTestId('username'),
      'No Spaces',
    );
    await fireEvent.changeText(screen.getByTestId('password'), 'short');
    await fireEvent.press(screen.getByTestId('account-submit'));
    expect(
      await screen.findByText(/lowercase letters, digits or underscores/),
    ).toBeTruthy();
    expect(screen.getByText('Use 8 to 128 characters.')).toBeTruthy();
    expect(tokenStore.get()).toBeNull();
  });
});

describe('session builder', () => {
  it('builds a lineup with the why, and the debug toggle switches the policy', async () => {
    // A returning user: the mock's GET /v1/me fixture has consent and preferences.
    tokenStore.set({ token: 'mock-token', expiresAt: FUTURE });
    await renderRouter(routes, { initialUrl: '/session' });

    await fireEvent.press(await screen.findByTestId('build-session'));
    expect(await screen.findByTestId('lineup-hero')).toBeTruthy();
    expect(screen.getByText('Why this route')).toBeTruthy();
    expect(screen.getByText('Matches your board and angle')).toBeTruthy();
    expect(screen.getByText(/Policy heuristic-v2/)).toBeTruthy();

    await fireEvent.press(screen.getByTestId('policy-heuristic-v1'));
    await fireEvent.press(screen.getByTestId('build-session'));
    await waitFor(() =>
      expect(screen.getByText(/Policy heuristic-v1/)).toBeTruthy(),
    );
    expect(sessionStore.getState().lineup?.policy_version).toBe('heuristic-v1');
  });

  it('sends a signed-out visitor to onboarding', async () => {
    await renderRouter(routes, { initialUrl: '/session' });
    expect(await screen.findByText('Choose a username')).toBeTruthy();
  });
});
