import type { ReportDefinition } from '@/api/reporting';
import { canAny } from '@/auth/access';

/** One entry in the report list: a report this page runs, or one that lives on its own page (↗). */
export interface ReportEntry {
  key: string;
  title: string;
  description: string;
  /** Set for a report on another page: where it opens. */
  to?: string;
}

export interface ReportArea {
  id: string;
  title: string;
  entries: ReportEntry[];
}

/** Reports that live on their own pages, listed here so nobody hunts for them (2026-11-09), with who may open them. */
const ELSEWHERE: { area: string; entry: ReportEntry; permissions: string[] }[] = [
  {
    area: 'accounting',
    entry: { key: 'ifrs17', title: 'IFRS 17 statements', description: 'Engine runs and their results.', to: '/staff/ifrs17-engine' },
    permissions: ['ifrs17.view'],
  },
  {
    area: 'reinsurance',
    entry: { key: 'treaty-statements', title: 'Treaty statements', description: 'Each treaty’s quarterly statement and settlement.', to: '/staff/reinsurance-statements' },
    permissions: ['reinsurance.view'],
  },
  {
    area: 'regulatory',
    entry: { key: 'tira-returns', title: 'TIRA returns', description: 'Regulatory returns, generated and filed.', to: '/staff/regulatory-returns' },
    permissions: ['returns.view'],
  },
];

const ORDER: { id: string; title: string }[] = [
  { id: 'accounting', title: 'Accounting' },
  { id: 'claims', title: 'Claims' },
  { id: 'policies', title: 'Policies' },
  { id: 'clients', title: 'Clients' },
  { id: 'reinsurance', title: 'Reinsurance' },
  { id: 'commissions', title: 'Commissions' },
  { id: 'solvency', title: 'Solvency & risk' },
  { id: 'regulatory', title: 'Regulatory' },
];

/** Which area a catalogue report belongs to. Later batches add their own categories; until then, by key. */
function areaOf(definition: ReportDefinition): string {
  if (definition.category === 'ACCOUNTING') return 'accounting';
  if (definition.category === 'CLAIMS') return 'claims';
  if (definition.key === 'clients') return 'clients';
  return 'policies';
}

/** The catalogue grouped into the list's areas, in the business's order; an area with nothing in it is left out. */
export function reportAreas(catalogue: readonly ReportDefinition[]): ReportArea[] {
  return ORDER.map(({ id, title }) => ({
    id,
    title,
    entries: [
      ...catalogue.filter((d) => areaOf(d) === id).map((d) => ({ key: d.key, title: d.title, description: d.description })),
      ...ELSEWHERE.filter((e) => e.area === id && canAny(...e.permissions)).map((e) => e.entry),
    ],
  })).filter((a) => a.entries.length > 0);
}

/** The areas whose entries match a search, each narrowed to its matches. */
export function searchAreas(areas: readonly ReportArea[], query: string): ReportArea[] {
  const q = query.trim().toLowerCase();
  if (!q) return [...areas];
  return areas
    .map((a) => ({ ...a, entries: a.entries.filter((e) => `${e.title} ${e.description}`.toLowerCase().includes(q)) }))
    .filter((a) => a.entries.length > 0);
}
