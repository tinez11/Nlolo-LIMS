/**
 * The year-end close (IFRS 17 I6): the console's rules for it. The server decides each of them again; these only keep
 * the screen from offering what it would refuse.
 */

export const CLOSE_STATUS_LABEL: Record<string, string> = {
  PREPARED: 'Awaiting approval',
  POSTED: 'Posted',
  REJECTED: 'Rejected',
  REPLACED: 'Replaced',
};

export const CLASS_LABEL: Record<string, string> = {
  '4': 'Insurance revenue',
  '5': 'Insurance service expenses',
  '6': 'Reinsurance held',
  '7': 'Finance and investment',
  '8': 'Other expenses and tax',
};

/** The year a close is usually for: the one that has just ended. */
export function lastYear(now: Date = new Date()): number {
  return now.getFullYear() - 1;
}

export const money = (n: number | null | undefined) =>
  n == null ? '—' : n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

/** The year's result in words: a profit, a loss or break-even. */
export function resultLabel(profit: number): string {
  if (profit > 0) return `Profit ${money(profit)}`;
  if (profit < 0) return `Loss ${money(-profit)}`;
  return 'Break-even';
}

/** A finance approver who did not prepare it decides a prepared close. */
export function canDecideClose(
  close: { status?: string | null; preparedBy?: string | null },
  viewer: string | null | undefined,
  approver: boolean,
): boolean {
  return approver && close.status === 'PREPARED' && viewer != null && close.preparedBy != null && viewer !== close.preparedBy;
}
