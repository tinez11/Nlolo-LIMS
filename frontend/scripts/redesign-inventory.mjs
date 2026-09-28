import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';

const root = 'src/features';
const files = [];
(function walk(dir) {
  for (const e of readdirSync(dir)) {
    const p = join(dir, e);
    if (statSync(p).isDirectory()) walk(p);
    else if (p.endsWith('.tsx') && !p.endsWith('.test.tsx')) files.push(p.split('\\').join('/'));
  }
})(root);

const rows = [];
for (const f of files) {
  const s = readFileSync(f, 'utf8');
  const renaming = (s.match(/status === 'loading' \?/g) ?? []).length;
  const rawAlert = (s.match(/role="alert"/g) ?? []).length;
  const backLink = /ArrowLeft/.test(s) ? 1 : 0;
  const hasHeader = /<PageHeader/.test(s);
  const hasCrumb = /breadcrumb=/.test(s);
  if (renaming || rawAlert || backLink) {
    rows.push({ f: f.replace('src/features/', ''), renaming, rawAlert, backLink, hasHeader, hasCrumb });
  }
}

const byDir = new Map();
for (const r of rows) {
  const d = r.f.split('/')[0];
  const g = byDir.get(d) ?? { files: 0, renaming: 0, rawAlert: 0, backLink: 0 };
  g.files++; g.renaming += r.renaming; g.rawAlert += r.rawAlert; g.backLink += r.backLink;
  byDir.set(d, g);
}

console.log('DIRECTORY        files  renaming  raw-alert  back-link');
for (const [d, g] of [...byDir].sort((a, b) => b[1].files - a[1].files)) {
  console.log(
    d.padEnd(16) + String(g.files).padStart(5) + String(g.renaming).padStart(10) +
    String(g.rawAlert).padStart(11) + String(g.backLink).padStart(11),
  );
}
const t = [...byDir.values()].reduce((a, g) => ({
  files: a.files + g.files, renaming: a.renaming + g.renaming,
  rawAlert: a.rawAlert + g.rawAlert, backLink: a.backLink + g.backLink,
}), { files: 0, renaming: 0, rawAlert: 0, backLink: 0 });
console.log('TOTAL'.padEnd(16) + String(t.files).padStart(5) + String(t.renaming).padStart(10) +
  String(t.rawAlert).padStart(11) + String(t.backLink).padStart(11));

console.log('\n--- per file ---');
for (const r of rows.sort((a, b) => a.f.localeCompare(b.f))) {
  console.log(
    '  ' + r.f.padEnd(50) +
    (r.renaming ? 'rename:' + r.renaming + ' ' : '') +
    (r.rawAlert ? 'alert:' + r.rawAlert + ' ' : '') +
    (r.backLink ? 'back ' : '') +
    (r.hasHeader && !r.hasCrumb ? '(header, no crumb)' : ''),
  );
}
