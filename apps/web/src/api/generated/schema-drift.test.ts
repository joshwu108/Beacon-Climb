// @vitest-environment node
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { mkdtemp, rm } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';

const here = resolve(fileURLToPath(import.meta.url), '..');
const spec = resolve(here, '../../../../../contracts/openapi.yaml');
const committed = resolve(here, 'schema.d.ts');

const require = createRequire(import.meta.url);
const pkgPath = require.resolve('openapi-typescript/package.json');
const pkg = JSON.parse(readFileSync(pkgPath, 'utf8')) as { bin: Record<string, string> | string };
const cli = resolve(pkgPath, '..', typeof pkg.bin === 'string' ? pkg.bin : pkg.bin['openapi-typescript']!);

describe('generated API schema', () => {
  let dir = '';
  beforeAll(async () => {
    dir = await mkdtemp(join(tmpdir(), 'beacon-schema-'));
  });
  afterAll(() => rm(dir, { recursive: true, force: true }));

  it('matches a fresh openapi-typescript run over contracts/openapi.yaml (run pnpm generate:api)', () => {
    const out = join(dir, 'schema.d.ts');
    execFileSync(process.execPath, [cli, spec, '-o', out], { stdio: 'pipe' });
    expect(readFileSync(out, 'utf8')).toBe(readFileSync(committed, 'utf8'));
  });

  it('is generated from contract version 1.1.0', () => {
    expect(readFileSync(spec, 'utf8')).toMatch(/^\s*version: 1\.1\.0$/m);
  });
});
