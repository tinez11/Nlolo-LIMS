import type { InvoiceView } from '@/api/types';

/**
 * What a customer can pay now (2026-10-08, the customer portal design step 6): everything late or part-paid, and the
 * next premium coming up. Billing raises a year of instalments ahead; offering all twelve would invite paying a year
 * early by mistake, so only the earliest one not yet due is shown.
 */
const LATE = new Set(['OVERDUE', 'IN_GRACE', 'PARTIALLY_PAID']);

export function payableNow(invoices: InvoiceView[]): InvoiceView[] {
  const open = invoices.filter((i) => i.invoiceId && i.dueDate && (LATE.has(i.status ?? '') || i.status === 'DUE'));
  const late = open.filter((i) => LATE.has(i.status ?? ''));
  const next = open.filter((i) => i.status === 'DUE').sort((a, b) => (a.dueDate ?? '').localeCompare(b.dueDate ?? ''))[0];
  return [...late, ...(next ? [next] : [])].sort((a, b) => (a.dueDate ?? '').localeCompare(b.dueDate ?? ''));
}

/** What is still owed on it: the balance due, else the amount. */
export function owed(invoice: InvoiceView): { amount: string; currency: string } | null {
  const money = invoice.balanceDue ?? invoice.amount;
  return money ? { amount: money.amount, currency: money.currencyCode } : null;
}

/** A Tanzanian mobile number as a mobile-money payer: 0XXXXXXXXX, 255XXXXXXXXX or +255XXXXXXXXX. */
export function isMobileNumber(value: string): boolean {
  return /^(\+?255|0)[67]\d{8}$/.test(value.replace(/\s+/g, ''));
}
