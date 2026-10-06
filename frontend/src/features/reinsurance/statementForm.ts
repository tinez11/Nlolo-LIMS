/**
 * The quarterly reinsurance statement's console helpers (IFRS 17 I3d). Pure: no React, no store.
 */

export const STATEMENT_STATUS_LABEL: Record<string, string> = {
  DRAFT: 'Draft',
  SUBMITTED: 'Awaiting approval',
  APPROVED: 'Posted',
  REJECTED: 'Rejected',
};

/** The guide's names for the three entries a statement posts. */
export const ENTRY_LABEL: Record<string, string> = {
  'R-01': 'R-01 Offset into the current account',
  'R-03': 'R-03 Profit commission',
  'R-04': 'R-04 Funds withheld',
};

const quarterIndex = (isoDate: string): number => {
  const [y, m] = isoDate.split('-').map(Number);
  return y * 4 + Math.floor((m - 1) / 3);
};

/**
 * Calendar quarters that have ended by `now` in Dar es Salaam, from the treaty's start (and not after its end), newest
 * first. The server decides what can really be settled; this only offers the buttons.
 */
export function endedQuarters(effectiveFrom: string, effectiveTo: string | null | undefined, now: Date = new Date()): string[] {
  const today = new Intl.DateTimeFormat('en-CA', { timeZone: 'Africa/Dar_es_Salaam' }).format(now); // YYYY-MM-DD
  const current = quarterIndex(today);
  const last = effectiveTo ? quarterIndex(effectiveTo) : Number.MAX_SAFE_INTEGER;
  const out: string[] = [];
  for (let index = quarterIndex(effectiveFrom); index < current && index <= last; index++) {
    out.push(`${Math.floor(index / 4)}-Q${(index % 4) + 1}`);
  }
  return out.reverse();
}

const money = (n: number) => n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

/** Which way the reinsurer current account (1434) falls once the statement posts. Amounts are decimal strings. */
export function settlementSide(s: { owedToUs: string; owedByUs: string }): string {
  const toUs = Number(s.owedToUs);
  const byUs = Number(s.owedByUs);
  if (toUs > 0) return `The reinsurer owes us ${money(toUs)}`;
  if (byUs > 0) return `We owe the reinsurer ${money(byUs)}`;
  return 'Settled: nothing owed either way';
}
