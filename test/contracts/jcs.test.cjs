// RFC 8785 (JSON Canonicalization Scheme) reference canonicalizer and fixture test.
// Plain Node, no dependencies. Java (BC-0109) consumes the same contracts/jcs/fixtures.v1.json.
// Rules (ADR-0002 "Canonical JSON and identifiers"): duplicate keys rejected, finite binary64
// only, keys sorted by UTF-16 code units, ECMAScript number formatting, no whitespace,
// UTF-8 bytes then SHA-256 lowercase hex.
'use strict';
const fs = require('fs');
const path = require('path');
const crypto = require('node:crypto');
const assert = require('node:assert/strict');

class JcsError extends Error {}

const WHITESPACE = new Set([' ', '\t', '\n', '\r']);
const NUMBER_RE = /^-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?/;

// Minimal recursive-descent JSON parser. Objects are returned as Map to preserve exact keys
// (including "__proto__") and to detect duplicates.
function parse(text) {
  if (typeof text !== 'string') throw new JcsError('input must be a string');
  let i = 0;
  const skip = () => { while (i < text.length && WHITESPACE.has(text[i])) i += 1; };
  const fail = (msg) => { throw new JcsError(`${msg} at offset ${i}`); };

  function parseValue() {
    skip();
    const c = text[i];
    if (c === '{') return parseObject();
    if (c === '[') return parseArray();
    if (c === '"') return parseString();
    if (c === 't' && text.startsWith('true', i)) { i += 4; return true; }
    if (c === 'f' && text.startsWith('false', i)) { i += 5; return false; }
    if (c === 'n' && text.startsWith('null', i)) { i += 4; return null; }
    if (c === '-' || (c >= '0' && c <= '9')) return parseNumber();
    return fail(`unexpected token ${JSON.stringify(c === undefined ? 'EOF' : c)}`);
  }

  function parseNumber() {
    const m = NUMBER_RE.exec(text.slice(i));
    if (!m) fail('malformed number');
    i += m[0].length;
    const n = Number(m[0]);
    if (!Number.isFinite(n)) fail('number is not a finite binary64');
    return n;
  }

  function parseString() {
    i += 1; // opening quote
    let out = '';
    for (;;) {
      if (i >= text.length) fail('unterminated string');
      const c = text[i];
      if (c === '"') { i += 1; return out; }
      if (c === '\\') {
        const e = text[i + 1];
        i += 2;
        if (e === '"') out += '"';
        else if (e === '\\') out += '\\';
        else if (e === '/') out += '/';
        else if (e === 'b') out += '\b';
        else if (e === 'f') out += '\f';
        else if (e === 'n') out += '\n';
        else if (e === 'r') out += '\r';
        else if (e === 't') out += '\t';
        else if (e === 'u') {
          const hex = text.slice(i, i + 4);
          if (!/^[0-9a-fA-F]{4}$/.test(hex)) fail('bad \\u escape');
          out += String.fromCharCode(parseInt(hex, 16));
          i += 4;
        } else fail('bad escape');
        continue;
      }
      if (c.charCodeAt(0) < 0x20) fail('unescaped control character in string');
      out += c;
      i += 1;
    }
  }

  function parseArray() {
    i += 1;
    const arr = [];
    skip();
    if (text[i] === ']') { i += 1; return arr; }
    for (;;) {
      arr.push(parseValue());
      skip();
      if (text[i] === ',') { i += 1; continue; }
      if (text[i] === ']') { i += 1; return arr; }
      fail('expected , or ]');
    }
  }

  function parseObject() {
    i += 1;
    const obj = new Map();
    skip();
    if (text[i] === '}') { i += 1; return obj; }
    for (;;) {
      skip();
      if (text[i] !== '"') fail('expected string key');
      const key = parseString();
      if (obj.has(key)) fail(`duplicate key ${JSON.stringify(key)}`);
      skip();
      if (text[i] !== ':') fail('expected :');
      i += 1;
      obj.set(key, parseValue());
      skip();
      if (text[i] === ',') { i += 1; continue; }
      if (text[i] === '}') { i += 1; return obj; }
      fail('expected , or }');
    }
  }

  const value = parseValue();
  skip();
  if (i !== text.length) fail('trailing data after JSON value');
  return value;
}

// UTF-16 code unit comparison; this is exactly JavaScript's default string ordering.
const compareUtf16 = (a, b) => (a < b ? -1 : a > b ? 1 : 0);

function serialize(value) {
  if (value === null) return 'null';
  if (typeof value === 'boolean') return value ? 'true' : 'false';
  if (typeof value === 'number') {
    if (!Number.isFinite(value)) throw new JcsError('non-finite number');
    return value === 0 ? '0' : value.toString(); // -0 → "0", ECMAScript Number::toString otherwise
  }
  if (typeof value === 'string') return JSON.stringify(value);
  if (Array.isArray(value)) return `[${value.map(serialize).join(',')}]`;
  if (value instanceof Map) {
    const keys = [...value.keys()].sort(compareUtf16);
    return `{${keys.map((k) => `${JSON.stringify(k)}:${serialize(value.get(k))}`).join(',')}}`;
  }
  throw new JcsError(`unsupported value type ${typeof value}`);
}

const canonicalize = (text) => serialize(parse(text));
const sha256Hex = (canonical) => crypto.createHash('sha256').update(Buffer.from(canonical, 'utf8')).digest('hex');

module.exports = { parse, serialize, canonicalize, sha256Hex, JcsError };

if (require.main === module) {
  const root = path.resolve(__dirname, '../..');
  const fixtures = JSON.parse(fs.readFileSync(path.join(root, 'contracts/jcs/fixtures.v1.json'), 'utf8'));
  let valid = 0;
  let invalid = 0;
  for (const f of fixtures.valid) {
    const canonical = canonicalize(f.input);
    assert.equal(canonical, f.canonical, `${f.name}: canonical form`);
    assert.equal(canonicalize(canonical), canonical, `${f.name}: idempotent`);
    assert.equal(sha256Hex(canonical), f.sha256, `${f.name}: sha256`);
    // JCS maps -0 to 0, so compare values with negative zero normalised.
    const noNegZero = (k, v) => (v === 0 ? 0 : v);
    assert.deepEqual(JSON.parse(canonical, noNegZero), JSON.parse(f.input, noNegZero), `${f.name}: value preserved`);
    valid += 1;
  }
  for (const f of fixtures.invalid) {
    assert.throws(() => canonicalize(f.input), JcsError, `${f.name}: must be rejected (${f.reason})`);
    invalid += 1;
  }
  // ADR-0002 cross-language fixture, asserted directly so the file cannot drift from the ADR.
  assert.equal(canonicalize('{"b":1.0,"a":1}'), '{"a":1,"b":1}', 'ADR-0002 fixture');
  // RFC 8785 §3.2.3 ordering: the surrogate pair (0xD83D) sorts before U+FB33 and after U+20AC.
  assert.equal(canonicalize('{"\\ufb33":1,"\\ud83d\\ude00":2,"\\u20ac":3}'), '{"\u20ac":3,"\ud83d\ude00":2,"\ufb33":1}', 'UTF-16 key order');
  console.log(`jcs fixtures: ${valid} valid canonicalized and hashed, ${invalid} invalid rejected`);
}
