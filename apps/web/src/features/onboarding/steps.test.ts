import { describe, expect, it } from 'vitest';
import meFixture from '../../../../../contracts/examples/getMe/response.json';
import type { Me } from '../auth/useMe';
import { firstIncompleteStep, isStepId, resolveStep } from './steps';

const me = meFixture.body as Me;
const fresh: Me = { ...me, consent: null, preferences: null };
const consented: Me = { ...me, preferences: null };
const revoked: Me = {
  ...me,
  consent: { ...me.consent!, revoked_at: '2026-10-15T00:00:00Z' },
};

describe('firstIncompleteStep (resume logic)', () => {
  it('starts at account without a token or profile', () => {
    expect(firstIncompleteStep(false, undefined)).toBe('account');
    expect(firstIncompleteStep(true, undefined)).toBe('account');
  });

  it('skips to consent for a fresh profile and to board once consent exists', () => {
    expect(firstIncompleteStep(true, fresh)).toBe('consent');
    expect(firstIncompleteStep(true, consented)).toBe('board');
  });

  it('treats revoked consent as missing', () => {
    expect(firstIncompleteStep(true, revoked)).toBe('consent');
  });

  it('is complete (null) when preferences exist', () => {
    expect(firstIncompleteStep(true, me)).toBeNull();
  });
});

describe('resolveStep', () => {
  it('redirects a completed step forward', () => {
    expect(resolveStep('account', 'consent', false)).toBe('consent');
    expect(resolveStep('consent', 'board', false)).toBe('board');
  });

  it('never jumps ahead of the first incomplete step', () => {
    expect(resolveStep('board', 'consent', false)).toBe('consent');
    expect(resolveStep('goals', 'account', true)).toBe('account');
  });

  it('allows goals after board only when a draft exists', () => {
    expect(resolveStep('goals', 'board', false)).toBe('board');
    expect(resolveStep('goals', 'board', true)).toBe('goals');
    expect(resolveStep('board', 'board', true)).toBe('board');
  });

  it('defaults to the first incomplete step without a URL step', () => {
    expect(resolveStep(undefined, 'board', false)).toBe('board');
  });

  it('isStepId guards URL params', () => {
    expect(isStepId('goals')).toBe(true);
    expect(isStepId('nope')).toBe(false);
    expect(isStepId(undefined)).toBe(false);
  });
});
