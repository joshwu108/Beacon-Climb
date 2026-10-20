import getMe from '../../../../../../contracts/examples/getMe/response.json';
import type { Me } from '../../auth/useMe';
import { firstIncompleteStep, validateAccount } from '../steps';

const returning = getMe.body as Me;

describe('firstIncompleteStep (resume logic, as in apps/web)', () => {
  it('no token or no profile -> account', () => {
    expect(firstIncompleteStep(false, returning)).toBe('account');
    expect(firstIncompleteStep(true, undefined)).toBe('account');
  });

  it('no or revoked consent -> consent', () => {
    expect(firstIncompleteStep(true, { ...returning, consent: null })).toBe(
      'consent',
    );
    expect(
      firstIncompleteStep(true, {
        ...returning,
        consent: { ...returning.consent!, revoked_at: '2026-10-15T00:00:00Z' },
      }),
    ).toBe('consent');
  });

  it('consent but no preferences -> board; both -> done', () => {
    expect(firstIncompleteStep(true, { ...returning, preferences: null })).toBe(
      'board',
    );
    expect(firstIncompleteStep(true, returning)).toBeNull();
  });
});

describe('validateAccount', () => {
  it('accepts the contract pattern and length', () => {
    expect(validateAccount('chalk_hands', 'long-enough')).toEqual({});
  });

  it('names each field that is wrong', () => {
    expect(validateAccount('Chalk Hands', 'short')).toEqual({
      username: expect.any(String),
      password: expect.any(String),
    });
  });
});
