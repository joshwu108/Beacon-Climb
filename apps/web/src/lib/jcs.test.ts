// @vitest-environment node
import { describe, expect, it } from 'vitest';
import fixtures from '../../../../contracts/jcs/fixtures.v1.json';
import {
  JcsError,
  canonicalize,
  fingerprint,
  serialize,
  sha256Hex,
} from './jcs';

const noNegZero = (_key: string, value: unknown): unknown =>
  value === 0 ? 0 : value;

describe('RFC 8785 canonicaliser against contracts/jcs/fixtures.v1.json', () => {
  it('loads the pinned fixture file', () => {
    expect(fixtures.version).toBe('1.0.0');
    expect(fixtures.valid.length).toBeGreaterThan(0);
    expect(fixtures.invalid.length).toBeGreaterThan(0);
  });

  describe.each(fixtures.valid)('valid: $name', (f) => {
    it('canonicalises to the pinned text', () => {
      expect(canonicalize(f.input)).toBe(f.canonical);
    });
    it('is idempotent', () => {
      expect(canonicalize(f.canonical)).toBe(f.canonical);
    });
    it('hashes to the pinned sha256', async () => {
      await expect(sha256Hex(f.canonical)).resolves.toBe(f.sha256);
    });
    it('preserves the value', () => {
      expect(JSON.parse(canonicalize(f.input), noNegZero)).toEqual(
        JSON.parse(f.input, noNegZero),
      );
    });
  });

  describe.each(fixtures.invalid)('invalid: $name ($reason)', (f) => {
    it('is rejected with JcsError', () => {
      expect(() => canonicalize(f.input)).toThrow(JcsError);
    });
  });

  it('ADR-0002 cross-language fixture', () => {
    expect(canonicalize('{"b":1.0,"a":1}')).toBe('{"a":1,"b":1}');
  });

  it('sorts keys by UTF-16 code units (surrogate pair before U+FB33, after U+20AC)', () => {
    expect(canonicalize('{"\\ufb33":1,"\\ud83d\\ude00":2,"\\u20ac":3}')).toBe(
      '{"€":3,"😀":2,"דּ":1}',
    );
  });

  it('serialises plain objects the same way as parsed text and drops undefined members', () => {
    expect(serialize({ b: 1.0, a: 1, skipped: undefined })).toBe(
      '{"a":1,"b":1}',
    );
  });

  it('fingerprint(body) is sha256 of the canonical UTF-8 bytes for text and objects alike', async () => {
    const pinned = fixtures.valid.find(
      (f) => f.name === 'adr_0002_cross_language',
    )!;
    await expect(fingerprint(pinned.input)).resolves.toBe(pinned.sha256);
    await expect(fingerprint({ b: 1.0, a: 1 })).resolves.toBe(pinned.sha256);
  });
});
