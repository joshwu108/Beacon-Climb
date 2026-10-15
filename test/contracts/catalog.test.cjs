// Catalog interchange v1 (BC-0102): schema + cross-line rules for JSON Lines catalog files.
// Plain Node: ajv 2020 + ajv-formats for per-line validation, the RFC 8785 canonicaliser
// from jcs.test.cjs for manifest.content_sha256. Exit non-zero on any failure.
'use strict';
const fs = require('fs');
const path = require('path');
const assert = require('node:assert/strict');
const Ajv = require('ajv/dist/2020');
const addFormats = require('ajv-formats');
const { canonicalize, sha256Hex } = require('./jcs.test.cjs');

const root = path.resolve(__dirname, '../..');
const schema = JSON.parse(fs.readFileSync(path.join(root, 'contracts/catalog/catalog-v1.schema.json'), 'utf8'));
const ajv = new Ajv({ strict: false, allErrors: true });
addFormats(ajv);
const validateLine = ajv.compile(schema);
const branch = (name) => ajv.compile({ $ref: `${schema.$id}#/$defs/${name}` });
const validateManifest = branch('manifest');

class CatalogError extends Error {
  constructor(code, line, detail) {
    super(`${code} (line ${line})${detail ? ': ' + detail : ''}`);
    this.code = code;
    this.line = line;
  }
}
const fail = (code, line, detail) => { throw new CatalogError(code, line, detail); };

// Validates one catalog file end to end. Returns {manifest, boards, routes} or throws CatalogError.
function validateCatalogFile(file) {
  const text = fs.readFileSync(file, 'utf8');
  if (text.includes('\r')) fail('CRLF_NOT_ALLOWED', 0);
  const lines = text.split('\n');
  if (lines.length && lines[lines.length - 1] === '') lines.pop(); // one trailing LF is allowed
  if (lines.length === 0) fail('EMPTY_FILE', 0);
  const records = lines.map((raw, i) => {
    if (raw.trim() === '') fail('BLANK_LINE', i + 1);
    let value;
    try { value = JSON.parse(raw); } catch (e) { fail('NOT_JSON', i + 1, e.message); }
    if (!validateLine(value)) fail('SCHEMA_INVALID', i + 1, ajv.errorsText(validateLine.errors));
    return value;
  });
  const manifest = records[0];
  if (manifest.type !== 'manifest' || !validateManifest(manifest)) fail('MANIFEST_FIRST', 1);
  // Redundant with the schema's if/then, kept so the rule survives a loosened schema.
  if (manifest.synthetic !== (manifest.license_review_status === 'SYNTHETIC')) fail('SYNTHETIC_LICENSE_RULE', 1);
  if (records.slice(1).some((r) => r.type === 'manifest')) fail('DUPLICATE_MANIFEST', 1 + records.slice(1).findIndex((r) => r.type === 'manifest') + 1);

  const boards = new Map();
  const routes = new Set();
  let seenRoute = false;
  records.slice(1).forEach((rec, idx) => {
    const line = idx + 2;
    if (rec.type === 'board') {
      if (seenRoute) fail('BOARD_AFTER_ROUTE', line);
      if (boards.has(rec.board_id)) fail('DUPLICATE_BOARD', line, rec.board_id);
      if (rec.synthetic !== manifest.synthetic) fail('BOARD_SYNTHETIC_MISMATCH', line);
      const holds = new Set();
      for (const h of rec.holds) {
        if (holds.has(h.provider_hold_id)) fail('DUPLICATE_HOLD', line, h.provider_hold_id);
        holds.add(h.provider_hold_id);
      }
      boards.set(rec.board_id, { holds, angles: new Set(rec.available_angles) });
      return;
    }
    seenRoute = true;
    const board = boards.get(rec.board_id);
    if (!board) fail('UNKNOWN_BOARD', line, rec.board_id);
    const key = `${rec.board_id}\u0000${rec.provider_route_uuid}`;
    if (routes.has(key)) fail('DUPLICATE_ROUTE', line, rec.provider_route_uuid);
    routes.add(key);
    const placementKeys = new Set();
    for (const p of rec.placements) {
      if (!board.holds.has(p.provider_hold_id)) fail('UNKNOWN_HOLD', line, p.provider_hold_id);
      const pk = `${p.provider_hold_id}\u0000${p.role}`;
      if (placementKeys.has(pk)) fail('DUPLICATE_PLACEMENT', line, pk);
      placementKeys.add(pk);
    }
    const angles = new Set();
    for (const v of rec.variants) {
      if (angles.has(v.angle_deg)) fail('DUPLICATE_ANGLE', line, String(v.angle_deg));
      angles.add(v.angle_deg);
      if (!board.angles.has(v.angle_deg)) fail('ANGLE_NOT_AVAILABLE', line, String(v.angle_deg));
      if (v.grade_numeric === null && v.grade_observation_count !== 0) fail('UNGRADED_WITH_OBSERVATIONS', line);
    }
  });
  if (manifest.record_counts.boards !== boards.size || manifest.record_counts.routes !== routes.size) fail('RECORD_COUNTS_MISMATCH', 1, `boards=${boards.size} routes=${routes.size}`);
  // README rule: sha256(utf8(join(LF, canonical(line) for every line after the manifest))), no trailing LF.
  const digest = sha256Hex(lines.slice(1).map(canonicalize).join('\n'));
  if (digest !== manifest.content_sha256) fail('CONTENT_SHA256_MISMATCH', 1, `computed ${digest}`);
  return { manifest, boards: boards.size, routes: routes.size };
}

module.exports = { validateCatalogFile, CatalogError };

if (require.main === module) {
  const examples = path.join(root, 'contracts/catalog/examples');
  // Valid example.
  const mini = validateCatalogFile(path.join(examples, 'synthetic-mini.jsonl'));
  assert.equal(mini.boards, 1, 'mini: one board');
  assert.equal(mini.routes, 2, 'mini: two routes');
  assert.equal(mini.manifest.synthetic, true, 'mini: synthetic');
  // Negative examples: file name -> expected failure code. Every file in invalid/ must be listed.
  const expected = {
    'manifest-synthetic-false-license-synthetic.jsonl.txt': 'SCHEMA_INVALID',
    'manifest-missing-synthetic.jsonl.txt': 'SCHEMA_INVALID',
    'route-unknown-role.jsonl.txt': 'SCHEMA_INVALID',
    'route-colon-in-uuid.jsonl.txt': 'SCHEMA_INVALID',
    'first-line-board.jsonl.txt': 'MANIFEST_FIRST',
    'wrong-content-sha256.jsonl.txt': 'CONTENT_SHA256_MISMATCH',
    'route-before-board.jsonl.txt': 'UNKNOWN_BOARD',
    'route-unknown-hold.jsonl.txt': 'UNKNOWN_HOLD',
    'route-angle-not-available.jsonl.txt': 'ANGLE_NOT_AVAILABLE',
    'record-counts-mismatch.jsonl.txt': 'RECORD_COUNTS_MISMATCH',
  };
  const invalidDir = path.join(examples, 'invalid');
  const files = fs.readdirSync(invalidDir).sort();
  assert.deepEqual(files, Object.keys(expected).sort(), 'every invalid fixture has an expected failure code');
  let rejected = 0;
  for (const file of files) {
    assert.ok(file.endsWith('.jsonl.txt'), `${file}: invalid fixtures must use .jsonl.txt (CI licensed-data guard)`);
    let error;
    try { validateCatalogFile(path.join(invalidDir, file)); } catch (e) { error = e; }
    assert.ok(error instanceof CatalogError, `${file}: must be rejected`);
    assert.equal(error.code, expected[file], `${file}: ${error.message}`);
    rejected += 1;
  }
  // Schema-level checks that the README promises.
  assert.equal(schema.$defs.manifest.additionalProperties, false, 'manifest is closed');
  assert.ok(!validateManifest({ ...mini.manifest, synthetic: false }), 'synthetic=false with SYNTHETIC rejected');
  assert.ok(validateManifest({ ...mini.manifest, synthetic: false, license_review_status: 'LOCAL_RESEARCH_ONLY' }), 'synthetic=false with LOCAL_RESEARCH_ONLY accepted by schema (importer gates it)');
  assert.ok(!validateManifest({ ...mini.manifest, license_review_status: 'LOCAL_RESEARCH_ONLY' }), 'synthetic=true with LOCAL_RESEARCH_ONLY rejected');
  // BC-0104 producer output must validate (both producers pass validation in CI).
  const demo = validateCatalogFile(path.join(root, 'ml/datasets/synthetic/fixtures/demo-board-v1.jsonl'));
  assert.equal(demo.manifest.source, 'synthetic-demo', 'demo: source');
  assert.equal(demo.manifest.version, 'demo-board-v1', 'demo: version');
  assert.equal(demo.boards, 1, 'demo: one board');
  assert.ok(demo.routes >= 1, 'demo: routes');
  console.log(`catalog v1: schema compiled; synthetic-mini valid; ${rejected} invalid examples rejected; demo-board-v1 fixture valid (${demo.routes} routes)`);
}
