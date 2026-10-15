/**
 * RFC 8785 JSON Canonicalization Scheme (JCS) for the web client.
 *
 * Mirrors test/contracts/jcs.test.cjs (the reference) and the Java implementation
 * (BC-0109) so all three hash identical bytes; contracts/jcs/fixtures.v1.json is the
 * shared conformance suite (ADR-0002). Rules: duplicate keys rejected, finite binary64
 * only, keys sorted by UTF-16 code units, ECMAScript Number::toString, JSON.stringify
 * string escapes, no whitespace, UTF-8 bytes then SHA-256 lowercase hex.
 */

export class JcsError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'JcsError';
  }
}

/** Parsed JSON value. Objects are Maps so exact keys (including "__proto__") survive. */
export type JcsValue =
  | null
  | boolean
  | number
  | string
  | JcsValue[]
  | Map<string, JcsValue>;

const WHITESPACE = new Set([' ', '\t', '\n', '\r']);
const NUMBER_RE = /^-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?/;
const SIMPLE_ESCAPES: Readonly<Record<string, string>> = {
  '"': '"',
  '\\': '\\',
  '/': '/',
  b: '\b',
  f: '\f',
  n: '\n',
  r: '\r',
  t: '\t',
};

/** Minimal recursive-descent JSON parser that rejects duplicate keys and non-finite numbers. */
export const parse = (text: string): JcsValue => {
  if (typeof text !== 'string') throw new JcsError('input must be a string');
  let i = 0;

  const fail = (msg: string): never => {
    throw new JcsError(`${msg} at offset ${i}`);
  };
  const skip = (): void => {
    while (i < text.length && WHITESPACE.has(text[i]!)) i += 1;
  };

  const parseNumber = (): number => {
    const m = NUMBER_RE.exec(text.slice(i));
    if (!m) return fail('malformed number');
    i += m[0].length;
    const n = Number(m[0]);
    if (!Number.isFinite(n)) return fail('number is not a finite binary64');
    return n;
  };

  const parseEscape = (): string => {
    const e = text[i + 1];
    i += 2;
    if (e !== undefined && e in SIMPLE_ESCAPES) return SIMPLE_ESCAPES[e]!;
    if (e !== 'u') return fail('bad escape');
    const hex = text.slice(i, i + 4);
    if (!/^[0-9a-fA-F]{4}$/.test(hex)) return fail('bad \\u escape');
    i += 4;
    return String.fromCharCode(parseInt(hex, 16));
  };

  const parseString = (): string => {
    i += 1; // opening quote
    let out = '';
    for (;;) {
      if (i >= text.length) return fail('unterminated string');
      const c = text[i]!;
      if (c === '"') {
        i += 1;
        return out;
      }
      if (c === '\\') {
        out += parseEscape();
        continue;
      }
      if (c.charCodeAt(0) < 0x20)
        return fail('unescaped control character in string');
      out += c;
      i += 1;
    }
  };

  const parseArray = (): JcsValue[] => {
    i += 1;
    const arr: JcsValue[] = [];
    skip();
    if (text[i] === ']') {
      i += 1;
      return arr;
    }
    for (;;) {
      arr.push(parseValue());
      skip();
      if (text[i] === ',') {
        i += 1;
        continue;
      }
      if (text[i] === ']') {
        i += 1;
        return arr;
      }
      return fail('expected , or ]');
    }
  };

  const parseObject = (): Map<string, JcsValue> => {
    i += 1;
    const obj = new Map<string, JcsValue>();
    skip();
    if (text[i] === '}') {
      i += 1;
      return obj;
    }
    for (;;) {
      skip();
      if (text[i] !== '"') return fail('expected string key');
      const key = parseString();
      if (obj.has(key)) return fail(`duplicate key ${JSON.stringify(key)}`);
      skip();
      if (text[i] !== ':') return fail('expected :');
      i += 1;
      obj.set(key, parseValue());
      skip();
      if (text[i] === ',') {
        i += 1;
        continue;
      }
      if (text[i] === '}') {
        i += 1;
        return obj;
      }
      return fail('expected , or }');
    }
  };

  const parseValue = (): JcsValue => {
    skip();
    const c = text[i];
    if (c === '{') return parseObject();
    if (c === '[') return parseArray();
    if (c === '"') return parseString();
    if (c === 't' && text.startsWith('true', i)) {
      i += 4;
      return true;
    }
    if (c === 'f' && text.startsWith('false', i)) {
      i += 5;
      return false;
    }
    if (c === 'n' && text.startsWith('null', i)) {
      i += 4;
      return null;
    }
    if (c === '-' || (c !== undefined && c >= '0' && c <= '9'))
      return parseNumber();
    return fail(
      `unexpected token ${JSON.stringify(c === undefined ? 'EOF' : c)}`,
    );
  };

  const value = parseValue();
  skip();
  if (i !== text.length) fail('trailing data after JSON value');
  return value;
};

/** UTF-16 code unit comparison; exactly JavaScript's default string ordering. */
const compareUtf16 = (a: string, b: string): number =>
  a < b ? -1 : a > b ? 1 : 0;

const serializeNumber = (value: number): string => {
  if (!Number.isFinite(value)) throw new JcsError('non-finite number');
  return value === 0 ? '0' : value.toString(); // -0 → "0", ECMAScript Number::toString otherwise
};

/** Serialise a parsed value, or any plain JSON-compatible JS value, as JCS text. */
export const serialize = (value: unknown): string => {
  if (value === null) return 'null';
  if (typeof value === 'boolean') return value ? 'true' : 'false';
  if (typeof value === 'number') return serializeNumber(value);
  if (typeof value === 'string') return JSON.stringify(value);
  if (Array.isArray(value)) return `[${value.map(serialize).join(',')}]`;
  if (value instanceof Map) {
    const keys = [...value.keys()].sort(compareUtf16);
    return `{${keys.map((k) => `${JSON.stringify(k)}:${serialize(value.get(k))}`).join(',')}}`;
  }
  if (typeof value === 'object') {
    const record = value as Record<string, unknown>;
    const keys = Object.keys(record)
      .filter((k) => record[k] !== undefined)
      .sort(compareUtf16);
    return `{${keys.map((k) => `${JSON.stringify(k)}:${serialize(record[k])}`).join(',')}}`;
  }
  throw new JcsError(`unsupported value type ${typeof value}`);
};

/** Canonicalise raw JSON text. Throws JcsError on invalid input. */
export const canonicalize = (text: string): string => serialize(parse(text));

const toHex = (bytes: Uint8Array): string =>
  Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');

/** SHA-256 of bytes (or of a UTF-8 encoded string) as 64 lowercase hex characters. */
export const sha256Hex = async (
  input: Uint8Array | string,
): Promise<string> => {
  const bytes =
    typeof input === 'string' ? new TextEncoder().encode(input) : input;
  const digest = await crypto.subtle.digest('SHA-256', bytes as BufferSource);
  return toHex(new Uint8Array(digest));
};

/**
 * Request fingerprint for idempotent writes: sha256(utf8(JCS(body))). `body` may be raw
 * JSON text (validated by the parser) or a plain JS object (serialised directly).
 */
export const fingerprint = async (body: string | object): Promise<string> =>
  sha256Hex(typeof body === 'string' ? canonicalize(body) : serialize(body));
