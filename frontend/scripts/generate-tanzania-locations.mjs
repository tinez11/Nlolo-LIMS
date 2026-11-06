// node scripts/generate-tanzania-locations.mjs <mainland.json> <zanzibar.json> <out.ts>
// Step 4 of the rebuild described at the top of parse-nbs-census.mjs.
import fs from 'node:fs';
const [mainlandFile, zanzibarFile, outFile] = process.argv.slice(2);
const mainland = JSON.parse(fs.readFileSync(mainlandFile, 'utf8'));
const zanzibar = JSON.parse(fs.readFileSync(zanzibarFile, 'utf8'));

// The report's own spelling slips and PDF artefacts, corrected by hand after reading each in the source.
const COUNCIL_FIX = {
  'Sex, Sex Ratio, Number of Households and Average Household Size, Msalala District': 'Msalala District', // heading spelt "DISTICT"
  'Dar Es Salaam City': 'Dar es Salaam City',
  "Wanging'Ombe District": "Wanging'ombe District",
  "Nyang'Hwale District": "Nyang'hwale District",
};
const WARD_FIX = {
  "Longuo `B'": 'Longuo B',
  "Dule `M'": 'Dule M',
  'Dule "B"': 'Dule B',
  Mbamba_Bay: 'Mbamba Bay',
};

const byName = (a, b) => a.localeCompare(b, 'en', { sensitivity: 'base' });
const q = (s) => `'${s.replace(/\\/g, '\\\\').replace(/'/g, "\\'")}'`;

const regions = [...mainland, ...zanzibar]
  .map((r) => ({
    region: r.region.trim(),
    councils: r.councils
      .map((c) => ({
        name: COUNCIL_FIX[c.name] ?? c.name,
        wards: [...new Set(c.wards.map((w) => (WARD_FIX[w] ?? w).replace(/\s+/g, ' ').trim()))].sort(byName),
      }))
      .sort((a, b) => byName(a.name, b.name)),
  }))
  .sort((a, b) => byName(a.region, b.region));

const councilCount = regions.reduce((n, r) => n + r.councils.length, 0);
const wardCount = regions.reduce((n, r) => n + r.councils.reduce((k, c) => k + c.wards.length, 0), 0);

const body = regions
  .map((r) => [
    `  {`,
    `    region: ${q(r.region)},`,
    `    councils: [`,
    ...r.councils.map((c) => `      { name: ${q(c.name)}, wards: [${c.wards.map(q).join(', ')}] },`),
    `    ],`,
    `  },`,
  ].join('\n'))
  .join('\n');

const out = `/**
 * Tanzania's regions, councils and wards, for the address on a client's record.
 *
 * GENERATED -- do not edit by hand. Source: National Bureau of Statistics, 2022 Population and
 * Housing Census, "Administrative Units Population Distribution Report" (December 2022):
 *   Volume 1B, Tanzania Mainland -- ${mainland.length} regions, ${mainland.reduce((n, r) => n + r.councils.length, 0)} councils, ${mainland.reduce((n, r) => n + r.councils.reduce((k, c) => k + c.wards.length, 0), 0)} wards
 *   Volume 1C, Tanzania Zanzibar -- ${zanzibar.length} regions, ${zanzibar.reduce((n, r) => n + r.councils.length, 0)} councils, ${zanzibar.reduce((n, r) => n + r.councils.reduce((k, c) => k + c.wards.length, 0), 0)} shehia
 *   https://www.nbs.go.tz/statistics/subtopic/administrative-reports
 *
 * The report lists wards under COUNCILS (Moshi District, Moshi Municipal), not under the older
 * districts, so the "District" box offers councils: that is the level a ward belongs to. Zanzibar
 * has no wards; its lowest unit, the shehia, fills the same box.
 *
 * A handful of the report's own slips are corrected in the generator (a heading spelt "DISTICT",
 * Kibiti's table titled "Mafia", "Buhingwe" for Buhigwe, quote marks around ward letters).
 *
 * The address is stored as plain text, so a record written before this list may hold a value that
 * is not on it. The form keeps such a value, marked "not on the list", rather than wiping it.
 */
export interface Council {
  name: string;
  wards: readonly string[];
}

export const TANZANIA_REGIONS: readonly { region: string; councils: readonly Council[] }[] = [
${body}
];

/** ${regions.length} regions, ${councilCount} councils, ${wardCount} wards and shehia. */

/** The councils of a region, or none for a region not on the list (blank, or an older typed value). */
export function councilsOf(region: string): readonly Council[] {
  return TANZANIA_REGIONS.find((r) => r.region === region)?.councils ?? [];
}

/** The wards (shehia in Zanzibar) of a council in a region, or none when either is not on the list. */
export function wardsOf(region: string, council: string): readonly string[] {
  return councilsOf(region).find((c) => c.name === council)?.wards ?? [];
}

/** Whether a region is one of the listed ones -- false for blank and for an older typed value. */
export function isListedRegion(region: string): boolean {
  return TANZANIA_REGIONS.some((r) => r.region === region);
}
`;
fs.writeFileSync(outFile, out);
console.log(`wrote ${outFile}: ${regions.length} regions, ${councilCount} councils, ${wardCount} wards; ${(out.length / 1024).toFixed(0)} KB`);
