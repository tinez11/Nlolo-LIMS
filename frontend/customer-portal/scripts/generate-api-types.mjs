// Generates one TS module per OpenAPI spec the portal consumes. There is no aggregate spec on
// this platform (15 separate files, no springdoc endpoint), so there is no merge step either.
import { execFileSync } from 'node:child_process';
import { mkdirSync } from 'node:fs';
import path from 'node:path';

const SPECS = [
  'common', 'party', 'product', 'underwriting', 'policy',
  'billing', 'payment', 'claims', 'policyloan', 'document', 'refdata',
];

const specDir = path.resolve(import.meta.dirname, '../../../api/openapi');
const outDir = path.resolve(import.meta.dirname, '../src/types/api');
mkdirSync(outDir, { recursive: true });

for (const name of SPECS) {
  const input = path.join(specDir, `openapi-${name}.yaml`);
  const output = path.join(outDir, `${name}.ts`);
  execFileSync('npx', ['--yes', 'openapi-typescript', input, '-o', output], {
    stdio: 'inherit',
    shell: process.platform === 'win32',
  });
  console.log(`generated ${output}`);
}
