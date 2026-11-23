import { formatInstant } from '@/lib/dates';

/** Where a report reads live, in words (2026-11-11, analytics plan 2). */
export const LIVE_WORDS: Record<string, string> = {
  LEDGER: 'the ledger',
  BILLING: 'billing',
  DISTRIBUTION: 'agents and commission',
  REINSURANCE: 'reinsurance',
};

/** The reports' own copy, in words. */
export const COPY_WORDS = "the reports' own copy, updated as things happen";

const joined = (words: string[]) =>
  words.length <= 1 ? (words[0] ?? '') : `${words.slice(0, -1).join(', ')} and ${words[words.length - 1]}`;

/**
 * How fresh a report's figures are, in one line: the newest change the reports' copy had taken in, and what is read
 * live. Null when the report says nothing of either (no sources declared).
 *
 * @param companyWide a report that cannot be cut by branch says so first (2026-11-23, branch views), where the branch
 *                    choice would otherwise be: "Company-wide · Read live from the ledger"
 */
export function freshnessLine(sources: readonly string[], dataUpTo: string | null | undefined,
  companyWide = false): string | null {
  const live = joined(sources.filter((s) => s in LIVE_WORDS).map((s) => LIVE_WORDS[s]!));
  let line: string | null;
  if (sources.includes('REPORTING_COPY')) {
    const copy = dataUpTo ? `Last change taken in: ${formatInstant(dataUpTo)}` : 'Nothing taken in yet';
    line = live ? `${copy} · ${live} read live` : copy;
  } else {
    line = live ? `Read live from ${live}` : null;
  }
  if (!companyWide) return line;
  return line ? `Company-wide · ${line}` : 'Company-wide';
}

/** Where a report's figures come from, in words, for "What the figures mean". */
export function sourcesInWords(sources: readonly string[]): string {
  return joined(sources.map((s) => (s === 'REPORTING_COPY' ? COPY_WORDS : (LIVE_WORDS[s] ?? s))));
}
