# JCS (RFC 8785) fixtures

Beacon Climb hashes JSON only after canonicalising it with the JSON Canonicalization Scheme (RFC 8785), per ADR-0002 "Canonical JSON and identifiers". JCS bytes are used for:

- **Request fingerprints** — `POST /v1/me/attempts` idempotency: the same `Idempotency-Key` with a different fingerprint is `409 IDEMPOTENCY_CONFLICT`.
- **Artifact digests** — `candidate_set_digest = sha256(snapshot bytes)` and the stored snapshot file itself (ADR-0003); `environment_digest` likewise.

Raw request bytes are never hashed. Semantic schema validation precedes hashing.

## Rules

1. Parse the JSON text; **reject duplicate object keys** (compared after unescaping, so `"a"` and `"a"` collide).
2. Numbers must be **finite IEEE-754 binary64**; `NaN`, `Infinity` and values that overflow (`1e400`) are rejected. Integer-typed fields must additionally be inside the safe-integer range (`|n| <= 2^53 - 1`); this is a schema rule enforced before hashing, not a canonicaliser fixture.
3. Serialise object members **sorted by key, comparing UTF-16 code units** (RFC 8785 §3.2.3). JavaScript's default string comparison does this; Java must compare `char` values, not code points, and must not use a locale collator. Consequence: a surrogate pair (U+D800–U+DFFF) sorts before BMP characters above U+DFFF, e.g. `😀` < `～` (U+FF5E).
4. Numbers use **ECMAScript `Number::toString`** formatting (RFC 8785 §3.2.2.3): `1`, `1.0`, `1e0` → `1`; `1e21` → `1e+21`; `1E-7` → `1e-7`; `-0` → `0`; `100000000000000000000` stays positional.
5. Strings use the JSON escape set: `\b \t \n \f \r \" \\`, other control characters U+0000–U+001F as lowercase `\u00xx`; everything else, including non-ASCII and U+007F, is emitted literally. `/` is not escaped.
6. **No whitespace** anywhere.
7. Encode as **UTF-8**, then **SHA-256**, printed as 64 lowercase hex characters.

## fixtures.v1.json

```json
{ "valid":   [{ "name", "input", "canonical", "sha256" }],
  "invalid": [{ "name", "input", "reason" }] }
```

`input` is raw JSON *text* (a string, so that duplicate keys and non-JSON literals can be represented). An implementation passes when, for every valid case, `canonicalize(input) === canonical`, `canonicalize(canonical) === canonical` and `sha256(utf8(canonical)) === sha256`, and every invalid case is rejected.

The expected values were produced by the reference implementation in `test/contracts/jcs.test.cjs` (`npm --prefix test/contracts test`), which also asserts the ADR-0002 cross-language case `{"b":1.0,"a":1}` → `{"a":1,"b":1}` directly. The Java implementation (BC-0109, test ID `BC-0109-JCS`) must consume this file unchanged. Adding a case is a minor version of the fixture file; changing an existing expected value is a contract change and needs integrator approval.
