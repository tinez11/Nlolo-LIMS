/**
 * P-19, the month's expense allocation (IFRS 17 I5b, month-end step 5): the console's rules for it. The server decides
 * each of them again; these only keep the screen from offering what it would refuse.
 */

export const ALLOCATION_STATUS_LABEL: Record<string, string> = {
  PREPARED: 'Awaiting approval',
  POSTED: 'Posted',
  REJECTED: 'Rejected',
  REPLACED: 'Replaced',
};

export const CATEGORY_LABEL: Record<string, string> = {
  MAINTENANCE: 'Maintenance',
  CLAIMS_HANDLING: 'Claims handling',
  ACQUISITION: 'Acquisition',
};

export const DRIVER_LABEL: Record<string, string> = {
  IN_FORCE: 'policies in force',
  CLAIMS: 'claims notified',
  ISSUED: 'policies issued',
  EQUAL: 'equal shares',
};

/** An amount typed by finance: blank is zero; null (refused) when negative, not a number or over two decimals. */
export function parseAmount(text: string): number | null {
  const t = text.replace(/,/g, '').trim();
  if (t === '') return 0;
  if (!/^\d+(\.\d{1,2})?$/.test(t)) return null;
  return Number(t);
}

/** Why "Create extract" waits, or null when the month has a posted allocation (a nil one counts). */
export function extractGate(allocations: { status: string }[]): string | null {
  return allocations.some((a) => a.status === 'POSTED')
    ? null
    : 'Step 5 first: the month needs a posted expense allocation, or an approved "no allocation this month", before its extract.';
}

/** A finance approver who did not prepare it decides a prepared allocation. */
export function canDecideAllocation(
  allocation: { status: string; preparedBy: string },
  viewer: string | null | undefined,
  approver: boolean,
): boolean {
  return approver && allocation.status === 'PREPARED' && viewer != null && viewer !== allocation.preparedBy;
}

export const money = (n: number | null | undefined) =>
  n == null ? '—' : n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
