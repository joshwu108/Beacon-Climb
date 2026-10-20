/**
 * @jest-environment node
 *
 * Same check as apps/web/src/api/generated/schema-drift.test.ts: the committed schema.d.ts
 * must equal a fresh openapi-typescript run (same pinned version) over contracts/openapi.yaml.
 */
import { execFileSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';

const spec = resolve(__dirname, '../../../../../contracts/openapi.yaml');
const committed = resolve(__dirname, 'schema.d.ts');

const pkgPath = require.resolve('openapi-typescript/package.json');
const pkg = JSON.parse(readFileSync(pkgPath, 'utf8')) as {
  version: string;
  bin: Record<string, string> | string;
};
const cli = resolve(
  pkgPath,
  '..',
  typeof pkg.bin === 'string' ? pkg.bin : pkg.bin['openapi-typescript']!,
);

describe('generated API schema', () => {
  let dir = '';
  beforeAll(() => {
    dir = mkdtempSync(join(tmpdir(), 'beacon-mobile-schema-'));
  });
  afterAll(() => rmSync(dir, { recursive: true, force: true }));

  it('matches a fresh openapi-typescript run over contracts/openapi.yaml (run pnpm generate:api)', () => {
    const out = join(dir, 'schema.d.ts');
    execFileSync(process.execPath, [cli, spec, '-o', out], { stdio: 'pipe' });
    expect(readFileSync(out, 'utf8')).toBe(readFileSync(committed, 'utf8'));
  });

  it('uses the same openapi-typescript version as apps/web', () => {
    const web = JSON.parse(
      readFileSync(resolve(__dirname, '../../../../web/package.json'), 'utf8'),
    ) as { devDependencies: Record<string, string> };
    expect(pkg.version).toBe(web.devDependencies['openapi-typescript']);
  });

  it('is generated from contract version 1.3.0', () => {
    expect(readFileSync(spec, 'utf8')).toMatch(/^\s*version: 1\.3\.0$/m);
  });
});
