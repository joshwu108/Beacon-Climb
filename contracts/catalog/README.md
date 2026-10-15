# Catalog interchange v1 (JSON Lines)

`catalog-v1.schema.json` (BC-0102) is the single format every catalog producer writes and the
importer (BC-0105) reads: the synthetic demo generator (`ml/datasets/synthetic/generate.py`,
BC-0104) and the Kilter extractor (BC-0103). It carries everything the Flyway V1 tables need
(`provenance_sources`, `boards`, `holds`, `routes`, `route_variants`, `route_placements`).
`grade-scale.v1.json` in this directory is a separate contract (BC-0014) and is not part of it.

## File layout

A catalog is a UTF-8 **JSON Lines** file: one JSON object per line, LF line endings only (no CR,
no blank lines, one optional trailing LF). The schema validates **one line**; it is a `oneOf`
over the three record types, discriminated by `type`.

1. **Line 1 is the manifest** (`type: "manifest"`), exactly one per file.
2. Then **every board** (`type: "board"`).
3. Then **every route** (`type: "route"`). A board after the first route is an error.

```jsonc
{"type":"manifest","schema_version":1,"source":"synthetic-demo","version":"demo-board-v1","content_sha256":"<64 hex>","extracted_at":"2026-10-14T00:00:00Z","synthetic":true,"license_review_status":"SYNTHETIC","record_counts":{"boards":1,"routes":2},"notes":"optional"}
{"type":"board","board_id":"synthetic-demo-1","provider":"synthetic-demo","layout_id":"1","size_id":"12x12","synthetic":true,"geometry":{"units":"NORMALIZED","width":1,"height":1,"y_axis":"UP"},"available_angles":[20,40],"holds":[{"provider_hold_id":"h001","x":0.042,"y":0.042,"geometry_meta":{"row":0,"col":0}}]}
{"type":"route","provider_route_uuid":"syn-route-0001","board_id":"synthetic-demo-1","name":"Crimson Arete","setter_pseudonym":null,"tags":["crimpy"],"active_from":"2026-01-01T00:00:00Z","active_to":null,"placements":[{"provider_hold_id":"h001","role":"START","placement_order":0}],"variants":[{"angle_deg":40,"grade_numeric":17.5,"grade_observation_count":12,"validation_status":"ACTIVE"}]}
```

### Records and their database targets

| Record / field | Rule | Target (V1 migration) |
|---|---|---|
| manifest `source`, `version`, `content_sha256`, `extracted_at`, `synthetic`, `license_review_status` | all required; `record_counts {boards, routes}` required; `notes` optional; no other keys | `provenance_sources` (`content_sha256` is UNIQUE there: the same file imports once) |
| board `board_id` | `^[a-z0-9-]{1,64}$`, unique in the file | `boards.board_id` |
| board `provider`, `layout_id`, `size_id` | strings 1..64 | `boards.*` |
| board `synthetic` | boolean, must equal the manifest flag | `boards.synthetic` |
| board `geometry {units: NORMALIZED\|MILLIMETRES, width>0, height>0, y_axis: UP\|DOWN}` | same shape as OpenAPI `BoardGeometry` | `boards.geometry_json` |
| board `available_angles` | unique integers in [-10, 70] | `boards.available_angles_json` |
| board `holds[] {provider_hold_id, x, y, geometry_meta?}` | ≥1; `provider_hold_id` unique within the board; `x`/`y` numbers in `geometry.units`, at most 3 decimals (stored as `numeric(8,3)`) | `holds` |
| route `provider_route_uuid` | `^[^:\s]{1,64}$` — no colon, no whitespace, because the public id is `<provider_route_uuid>:<angle_deg>` (ADR-0002); unique per board | `routes.provider_route_uuid` |
| route `board_id` | must name a board line earlier in the file | `routes.board_id` |
| route `name`, `setter_pseudonym` | string or null (required keys); never a real account name | `routes.name`, `routes.setter_pseudonym` |
| route `tags` | unique strings | `routes.tags_json` |
| route `active_from`, `active_to` | RFC 3339 date-time or null | `routes.active_from/active_to` |
| route `placements[] {provider_hold_id, role: START\|HAND\|FOOT\|FINISH, placement_order?≥0}` | ≥1; every hold exists in the board; `(provider_hold_id, role)` unique; in v1 placements are shared by all variants of the route and copied onto each `route_variants` row | `route_placements` |
| route `variants[] {angle_deg, grade_numeric, grade_observation_count, validation_status?}` | ≥1; `angle_deg` integer in [-10, 70], unique per route and in the board's `available_angles`; `grade_numeric` number or null (`numeric(5,2)`); `grade_observation_count` ≥0 and 0 when ungraded; `validation_status` ACTIVE (default) / RETIRED / INVALID | `route_variants` |

Every record uses `additionalProperties: false`; new fields need a schema change (see
Versioning).

## `content_sha256`

`manifest.content_sha256` is the SHA-256, as 64 lowercase hex characters, of:

```
utf8( join("\n", [ canonical(line) for line in lines[1:] ]) )
```

where `lines[1:]` are all lines after the manifest, in file order, `canonical()` is RFC 8785
(JCS) canonicalisation of that line's JSON value — keys sorted by UTF-16 code units, no
whitespace, ECMAScript number formatting (`1.0` → `1`, `0.0` → `0`), the JSON escape rules of
`contracts/jcs/README.md` — and the pieces are joined with a single LF with **no trailing LF**.
The manifest itself is not hashed. The raw bytes of the file are not hashed either: a producer may
write non-canonical lines (`examples/synthetic-mini.jsonl` does so on purpose) and the digest is
unchanged. Producers that know the record count up front can stream canonical lines through a
hash and then emit the manifest; otherwise buffer the records, hash, write the manifest, write
the records (what `generate.py` does).

Reference implementations: `test/contracts/catalog.test.cjs` (`validateCatalogFile`, using the
canonicaliser in `test/contracts/jcs.test.cjs`) and `canonical_json` / `content_sha256` in
`ml/datasets/synthetic/generate.py`. The Java importer must reproduce the digest with its
RFC 8785 implementation (BC-0109-JCS fixtures in `contracts/jcs/`) and refuse the file on
mismatch.

## Synthetic flag and licence gating

- `synthetic` and `license_review_status` are required. `synthetic: true` **if and only if**
  `license_review_status: "SYNTHETIC"`; the schema encodes this with `if/then/else` and the test
  checks it again as a cross-line rule. Each board's own `synthetic` flag must equal the
  manifest's.
- **Importer rule (planning/06 §2):** the importer refuses any file with `synthetic: false`
  unless `license_review_status` is `LOCAL_RESEARCH_ONLY` **and** the operator passes
  `--acknowledge-audit`. `REVIEWED_PERMITTED` is reserved for a source whose rights table in
  `docs/data-source-audit.md` has been signed by a human; no such source exists yet, so the
  importer should treat it like `LOCAL_RESEARCH_ONLY` until that decision is recorded.
- **CI guard:** `scripts/ci/check-no-licensed-data.sh` fails the build for any tracked `*.jsonl`
  or `*.ndjson` whose first line is not a JSON object with `"synthetic": true`. Consequently only
  synthetic catalogs may be committed, and negative fixtures whose first line is not such a
  manifest live under `examples/invalid/` with the extension **`.jsonl.txt`** so the guard
  skips them (all invalid fixtures use that extension for uniformity).

## Examples

- `examples/synthetic-mini.jsonl` — valid: manifest, one 2x2 board with 4 holds, two routes.
  Lines are deliberately not canonical (unsorted keys, `1.0`, spaces) to exercise the digest rule.
- `examples/invalid/*.jsonl.txt` — each fails `validateCatalogFile` with one specific code:

| File | Expected failure |
|---|---|
| `manifest-synthetic-false-license-synthetic` | `SCHEMA_INVALID` (if/then: synthetic=false needs a non-SYNTHETIC licence status) |
| `manifest-missing-synthetic` | `SCHEMA_INVALID` (required `synthetic`) |
| `route-unknown-role` | `SCHEMA_INVALID` (role `MATCH`) |
| `route-colon-in-uuid` | `SCHEMA_INVALID` (`provider_route_uuid` pattern) |
| `first-line-board` | `MANIFEST_FIRST` |
| `wrong-content-sha256` | `CONTENT_SHA256_MISMATCH` |
| `route-before-board` | `UNKNOWN_BOARD` (the route precedes its board) |
| `route-unknown-hold` | `UNKNOWN_HOLD` |
| `route-angle-not-available` | `ANGLE_NOT_AVAILABLE` |
| `record-counts-mismatch` | `RECORD_COUNTS_MISMATCH` |

Other codes the validator can raise: `CRLF_NOT_ALLOWED`, `EMPTY_FILE`, `BLANK_LINE`, `NOT_JSON`,
`SYNTHETIC_LICENSE_RULE`, `DUPLICATE_MANIFEST`, `BOARD_AFTER_ROUTE`, `DUPLICATE_BOARD`,
`BOARD_SYNTHETIC_MISMATCH`, `DUPLICATE_HOLD`, `DUPLICATE_ROUTE`, `DUPLICATE_PLACEMENT`,
`DUPLICATE_ANGLE`, `UNGRADED_WITH_OBSERVATIONS`. The importer should report the same conditions
(names are advisory; the conditions are normative).

Run: `npm --prefix test/contracts test` (runs `catalog.test.cjs`, which also validates the
BC-0104 fixture `ml/datasets/synthetic/fixtures/demo-board-v1.jsonl`).

## Versioning

`schema_version` is `const 1`. Within v1, producers may only gain **optional** fields after the
importer tolerates them, and that requires a schema edit plus a changelog line here (every record
is closed). Renaming or removing a field, changing a meaning, or changing the digest rule is v2
(`catalog-v2.schema.json`, `schema_version: 2`); the importer must keep reading v1. Changing an
example's expected outcome is a contract change and needs integrator approval.

## Changelog

**1.0** — 2026-10-15, BC-0102. Initial schema, digest rule, examples, validator.
