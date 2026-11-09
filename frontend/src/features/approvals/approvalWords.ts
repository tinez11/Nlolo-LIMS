import type { ApprovalRow } from '@/api/approvals';
import { formatInstantDay } from '@/lib/dates';

/**
 * Who asked, in words (2026-11-09: the page printed "30710ea6-369a-…" when the asker was no longer on the staff list).
 * A subject is never shown: a person reading a queue cannot do anything with one.
 */
export function askedBy(row: ApprovalRow): string {
  if (row.yours) return 'You — someone else signs';
  if (row.requestedByName) return row.requestedByName;
  if (row.requestedBy) return 'Someone not on the staff list';
  return 'Work queue';
}

/**
 * Days a kind may wait before it is overdue (2026-11-09). Money leaving the company, and money arriving that is not yet
 * confirmed, should not sit for more than two working days; prices, rates and the books have a week.
 */
const MONEY_KINDS = new Set([
  'bank-transfer', 'receipt', 'deposit-reversal', 'payout-review', 'payout-approval', 'payment-run', 'free-look',
  'surrender', 'account-withdrawal', 'unit-withdrawal', 'balance-withdrawal', 'balance-withdrawal-payee',
  'lender-refund', 'loan-payout', 'commission-payout', 'field-receipt', 'deposit-payee', 'term-account-payee',
  'reinsurance-settlement',
]);

export function overdueAfterDays(kind: string): number {
  return MONEY_KINDS.has(kind) ? 2 : 7;
}

const DAY_MS = 24 * 60 * 60 * 1000;

/** "Today", "1 day", "5 days", with the day it began and whether it is past its kind's limit. Null with no date. */
export function waiting(kind: string, requestedAt: string | null | undefined, now: Date = new Date()):
  { words: string; since: string; overdue: boolean } | null {
  if (!requestedAt) return null;
  const days = Math.max(0, Math.floor((now.getTime() - new Date(requestedAt).getTime()) / DAY_MS));
  return {
    words: days === 0 ? 'Today' : days === 1 ? '1 day' : `${days} days`,
    since: formatInstantDay(requestedAt),
    overdue: days > overdueAfterDays(kind),
  };
}

/** Whether a row answers the search: its description, policy, client or second line, case ignored. */
export function matches(row: ApprovalRow, query: string): boolean {
  const q = query.trim().toLowerCase();
  if (!q) return true;
  return [row.summary, row.policyNumber, row.clientName, row.detail]
    .some((field) => (field ?? '').toLowerCase().includes(q));
}
