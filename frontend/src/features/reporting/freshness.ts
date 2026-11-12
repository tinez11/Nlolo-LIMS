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
 */
export function freshnessLine(sources: readonly string[], dataUpTo: string | null | undefined): string | null {
  const live = joined(sources.filter((s) => s in LIVE_WORDS).map((s) => LIVE_WORDS[s]!));
  if (sources.includes('REPORTING_COPY')) {
    const copy = dataUpTo ? `Last change taken in: ${formatInstant(dataUpTo)}` : 'Nothing taken in yet';
    return live ? `${copy} · ${live} read live` : copy;
  }
  return live ? `Read live from ${live}` : null;
}

/** Where a report's figures come from, in words, for "What the figures mean". */
export function sourcesInWords(sources: readonly string[]): string {
  return joined(sources.map((s) => (s === 'REPORTING_COPY' ? COPY_WORDS : (LIVE_WORDS[s] ?? s))));
}
