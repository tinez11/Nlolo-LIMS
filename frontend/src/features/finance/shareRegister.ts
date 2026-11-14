import { formatMoney } from '@/lib/money';

/**
 * The shareholders register's helpers (2026-11-14). Pure. The split shown while an issue is typed is a preview of
 * what the server will post; the issue's own view carries the figures the server computed.
 */
export function issueSplit(shares: number, nominal: number, price: number) {
  const capital = Math.round(shares * nominal * 100) / 100;
  const total = Math.round(shares * price * 100) / 100;
  return { capital, premium: Math.round((total - capital) * 100) / 100, total };
}

export type CheckState = 'MET' | 'NOT_MET' | 'NOT_SET';

export function checkLabel(state: CheckState): string {
  return state === 'MET' ? 'Met' : state === 'NOT_SET' ? 'Not set' : 'Not met';
}

/** A holder's share of all issued shares, to one decimal. */
export function ownershipShare(held: number, all: number): string {
  return all === 0 ? '—' : `${((held * 100) / all).toFixed(1)}%`;
}

/** A number the server sent, in full, as TZS 1,000,000,000.00. */
export function tzs(amount: number | null | undefined): string {
  return formatMoney(amount == null ? null : { amount: amount.toFixed(2), currencyCode: 'TZS' });
}
