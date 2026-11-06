// Region -> council -> wards (shehia in Zanzibar) from the NBS 2022 PHC "Administrative Units
// Population Distribution Report", the first step in rebuilding src/features/party/tanzaniaLocations.ts.
//
//   1. Download Volume 1B (Mainland) and 1C (Zanzibar) from
//      https://www.nbs.go.tz/statistics/subtopic/administrative-reports
//   2. pdftotext -layout volume1b.pdf mainland.txt   (and the same for 1C -> zanzibar.txt)
//   3. node scripts/parse-nbs-census.mjs mainland.txt mainland.json
//      node scripts/parse-nbs-census.mjs zanzibar.txt zanzibar.json
//      It prints the totals and every numbering gap; the 2022 report gives 26/184/3,956 and
//      5/11/388. The two gaps left on Mainland (Bukoba repeats 23, Muleba skips 18) are the
//      report's own numbering, not lost wards.
//   4. node scripts/generate-tanzania-locations.mjs mainland.json zanzibar.json src/features/party/tanzaniaLocations.ts
import fs from 'node:fs';
const [inFile, outFile] = process.argv.slice(2);
const lines = fs.readFileSync(inFile, 'utf8').split(/\r?\n/);

const regions = [];
let region = null;
let council = null;
let inWardTable = false;
const problems = [];

const titleCase = (s) =>
  s.toLowerCase().replace(/(^|[\s/'-])([a-z])/g, (m, p, c) => p + c.toUpperCase());

for (let i = 0; i < lines.length; i++) {
  const raw = lines[i];
  const l = raw.trim();
  let m;
  if (l.includes('....')) continue; // table of contents
  if ((m = /^Region (\d+)\s*:\s*(.+)$/.exec(l))) {
    region = { region: m[2].trim(), councils: [] };
    regions.push(region);
    council = null;
    inWardTable = false;
    continue;
  }
  const open = (name, how) => {
    const clean = name.replace(/\s+Council$/i, '').replace(/[,.]+$/, '').trim();
    if (council && council.name.toLowerCase() === clean.toLowerCase()) { inWardTable = true; return; }
    // A table title straight after its section heading names the same council. Where they disagree the
    // heading is right (the report titles Kibiti's table "Mafia" and spells Buhigwe "Buhingwe").
    if (how === 'title' && council && council.how === 'heading' && council.wards.length === 0) {
      if (council.name.toLowerCase() !== clean.toLowerCase()) problems.push(`TITLE "${clean}" kept as heading "${council.name}"`);
      inWardTable = true;
      return;
    }
    council = { name: clean, wards: [], how, line: i + 1 };
    region.councils.push(council);
    inWardTable = true;
  };
  // A section heading: "3.4 MOSHI DISTRICT COUNCIL".
  if (region && (m = /^\d+\.\d+\s+([A-Z][A-Z' -]+ (?:DISTRICT|MUNICIPAL|TOWN|CITY)(?: COUNCIL)?)$/.exec(l))) {
    open(titleCase(m[1]), 'heading');
    continue;
  }
  // A table title: "... by Ward, Rombo District Council; 2022 PHC" (sometimes "Size Ward," or wrapped).
  // Zanzibar's lowest unit is the shehia: "... by Shehia, Wete Town Council; 2022 PHC".
  if (region && /\b(Ward|Shehia),/i.test(l)) {
    let title = l;
    if (!/PHC/.test(title)) title += ' ' + (lines[i + 1] ?? '').trim();
    const name = /(?:Ward|Shehia),\s*(.+?)\s*[;:]/i.exec(title)?.[1];
    if (name) open(name, 'title');
    continue;
  }
  // The report's own typos in ward numbers: "3.2 Kisiwani" (32), "14' Chanzuru", "21 Migoli", " 2 Nsalaga".
  if (council && inWardTable && (m = /^(\d)\.(\d)\s+([A-Z][a-z].*?)(?:\s{2,}[\d,].*)?$/.exec(l))) {
    council.wards.push({ n: Number(m[1] + m[2]), name: m[3].trim(), line: i + 1 });
    continue;
  }
  if (council && inWardTable && (m = /^(\d+)(?:\.+|'|,|\s)\s*([A-Za-z'].*?)(?:\s{2,}[\d,].*)?$/.exec(l))) {
    if (Number(m[1]) === 1 && council.wards.length > 0) {
      problems.push(`RESTART without a title after ${region.region} / ${council.name} at line ${i + 1}`);
    }
    let name = m[2].replace(/\s+[\d,]+(\s+[\d,.]+)*$/, '').trim();
    council.wards.push({ n: Number(m[1]), name, line: i + 1 });
  }
}

for (const r of regions) {
  for (const c of r.councils) {
    c.wards.forEach((w, k) => {
      if (w.n !== k + 1) problems.push(`${r.region} / ${c.name}: ward #${k + 1} is numbered ${w.n} (${w.name}, line ${w.line})`);
    });
    if (c.wards.length === 0) problems.push(`${r.region} / ${c.name}: no wards`);
  }
}

const councils = regions.reduce((n, r) => n + r.councils.length, 0);
const wards = regions.reduce((n, r) => n + r.councils.reduce((k, c) => k + c.wards.length, 0), 0);
console.log(`regions ${regions.length}, councils ${councils}, wards ${wards}`);
console.log(problems.length ? `PROBLEMS (${problems.length}):\n` + problems.slice(0, 60).join('\n') : 'no numbering gaps');
fs.writeFileSync(outFile, JSON.stringify(regions.map((r) => ({
  region: r.region,
  councils: r.councils.map((c) => ({ name: c.name, wards: c.wards.map((w) => w.name) })),
})), null, 1));
