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
  // Line by line, exactly as the guard tests it. Scanning the whole file instead lets `\s*`
  // span a newline, which matches a dropzone's multi-line status paragraph -- a live region
  // reporting "Uploading…" or "Drop the file", which is correct and is not a button label.
  const renaming = s
    .split(/\r?\n/)
    .filter((line) => /status === 'loading'\s*\?\s*'/.test(line)).length;
  // The ApiError shape, over a window of lines -- NOT every role="alert". Eight alerts on this
  // console are correct (form validation, strings from useState, one server-stored issuance
  // reason), and an earlier version of this script counted those, plus two comments explaining
  // why one of them is right. It reported ten defects where there were none, which is the
  // opposite of what a measurement is for. Matches `designGuards.test.ts`, which is the
  // authority; this script exists to show the shape of the work, not to define it.
  const lines = s.split(/\r?\n/);
  const rawAlert = lines.filter((_l, i) => {
    const window = lines.slice(i, i + 5).join(' ');
    return /role="alert"/.test(window) && /\.detail \?\?/.test(window) && /role="alert"/.test(lines[i]);
  }).length;
  const backLink = /<ArrowLeft[\s/>]/.test(s) ? 1 : 0;
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
