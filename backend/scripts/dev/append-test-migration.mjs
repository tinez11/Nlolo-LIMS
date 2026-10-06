#!/usr/bin/env node
// Appends one migration to every hand-written test migration list that already applies its module.
//
// Usage (from backend/):  node scripts/dev/append-test-migration.mjs <module>/<file>.sql [--dry-run]
//   e.g.                  node scripts/dev/append-test-migration.mjs product/V27__ifrs17_classification.sql
//
// Why: integration tests apply migrations from literal lists ("db-migrations/<module>/V..sql"), one list per class
// or helper. A new column on a table a JPA entity maps must reach every list that already applies that module's
// V1, or Hibernate selects a column the test database does not have. This inserts the new literal right after the
// LAST literal of the same module in each such file, so the module's own order is kept.
//
// Mechanics: if that last literal line ends with a comma, the new line is added after it with a comma; otherwise
// (it closes the call or array, e.g. `");` or `"};`) the old line takes a comma and the new line takes its ending.
// Files that mention the module's V1 more than once (two lists) are reported for review; only the last list in the
// file is extended.
import fs from 'node:fs';
import path from 'node:path';

const arg = process.argv[2];
const dryRun = process.argv.includes('--dry-run');
if (!arg || !/^[a-z_]+\/V\d+__[A-Za-z0-9_]+\.sql$/.test(arg)) {
  console.error('usage: append-test-migration.mjs <module>/V<n>__<name>.sql [--dry-run]');
  process.exit(2);
}
const module = arg.split('/')[0];
const literal = `db-migrations/${arg}`;
if (!fs.existsSync(literal)) {
  console.error(`no such migration: ${literal} (run from backend/)`);
  process.exit(2);
}

function walk(dir, out = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, entry.name);
    if (entry.isDirectory()) walk(p, out);
    else if (p.endsWith('.java')) out.push(p);
  }
  return out;
}

const v1Marker = `"db-migrations/${module}/V1__`;
const moduleMarker = `"db-migrations/${module}/V`;
const changed = [];
const review = [];

for (const file of walk('src/test/java')) {
  const original = fs.readFileSync(file, 'utf8');
  if (!original.includes(v1Marker) || original.includes(`"${literal}"`)) continue;
  const crlf = original.includes('\r\n');
  const lines = original.split(/\r?\n/);
  if (original.split(v1Marker).length > 2) review.push(file);

  let last = -1;
  lines.forEach((line, i) => {
    if (line.includes(moduleMarker)) last = i;
  });
  const line = lines[last];
  const quoteEnd = line.lastIndexOf('"');
  const head = line.slice(0, line.indexOf('"'));       // indentation (and anything before the literal)
  const tail = line.slice(quoteEnd + 1);               // what follows the closing quote
  let newLine;
  if (tail.trimEnd() === ',') {
    newLine = `${head}"${literal}",`;
  } else {
    lines[last] = `${line.slice(0, quoteEnd + 1)},`;
    newLine = `${head}"${literal}"${tail}`;
  }
  lines.splice(last + 1, 0, newLine);
  const updated = lines.join(crlf ? '\r\n' : '\n');
  if (!dryRun) fs.writeFileSync(file, updated);
  changed.push(file);
}

console.log(`${dryRun ? '[dry run] would change' : 'changed'} ${changed.length} file(s) for ${literal}`);
for (const f of changed) console.log(`  ${f}`);
if (review.length) {
  console.log(`review (more than one ${module} list; only the last was extended):`);
  for (const f of review) console.log(`  ${f}`);
}
