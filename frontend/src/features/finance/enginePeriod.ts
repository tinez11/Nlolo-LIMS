/**
 * The IFRS 17 engine period page's rules (IFRS 17 I5a). Pure: no React, no store. The server enforces every one of
 * them; these only decide what the page offers.
 */

export const RUN_STATUS_LABEL: Record<string, string> = {
  VALIDATED: 'Validated — awaiting approval',
  REJECTED: 'Rejected',
  POSTED: 'Posted',
  REPLACED: 'Replaced',
};

export const FIGURE_LABEL: Record<string, string> = {
  LRC: 'Liability for remaining coverage',
  LIC: 'Liability for incurred claims',
  CSM: 'Contractual service margin',
  ARC: 'Asset for remaining coverage',
  AIC: 'Asset for incurred claims',
  RI_CSM: 'Reinsurance CSM',
};

/** A finance approver decides a validated run they did not upload. */
export function canDecide(run: { status: string; uploadedBy: string }, viewer: string | null | undefined, isApprover: boolean): boolean {
  return isApprover && run.status === 'VALIDATED' && !!viewer && viewer !== run.uploadedBy;
}

/** A finance approver accepts an explanation someone else wrote. */
export function canAccept(row: { status: string; explainedBy?: string | null }, viewer: string | null | undefined, isApprover: boolean): boolean {
  return isApprover && row.status === 'EXPLAINED' && !!viewer && viewer !== row.explainedBy;
}

const money = (n: number) => Math.abs(n).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

/** What a reconciliation row's difference means for the period lock. */
export function differenceLabel(row: { status: string; difference: number }): string {
  switch (row.status) {
    case 'AGREED':
      return row.difference === 0 ? 'Agreed' : `Agreed (${money(row.difference)} rounding)`;
    case 'EXCEPTION':
      return `Differs by ${money(row.difference)} — explain it, or post a replacement run`;
    case 'EXPLAINED':
      return 'Explained — awaiting acceptance by a second person';
    case 'ACCEPTED':
      return 'Accepted';
    default:
      return row.status;
  }
}

/** The month that has just ended in Dar es Salaam: the one month-end work is usually about. */
export function previousMonth(now: Date = new Date()): string {
  const today = new Intl.DateTimeFormat('en-CA', { timeZone: 'Africa/Dar_es_Salaam' }).format(now);
  const [y, m] = today.split('-').map(Number);
  return m === 1 ? `${y - 1}-12` : `${y}-${String(m - 1).padStart(2, '0')}`;
}
