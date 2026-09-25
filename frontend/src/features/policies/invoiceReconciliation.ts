import type { InvoiceView } from '@/api/types';
import { compareAmounts } from '@/lib/money';

/**
 * Whether an invoice still has anything to pay. Unknown balance (an older response) counts as
 * owing, so the request stays offered and the backend -- which checks -- decides.
 */
export function owesSomething(invoice: Pick<InvoiceView, 'balanceDue' | 'status'>): boolean {
  if (invoice.status === 'WAIVED') return false;
  if (!invoice.balanceDue) return true;
  return compareAmounts(invoice.balanceDue.amount, '0.00') > 0;
}

/** True when the invoice has moved at all -- credited or paid -- so the breakdown is worth showing. */
export function hasMovement(invoice: Pick<InvoiceView, 'amountCredited' | 'amountPaid'>): boolean {
  const moved = (m: { amount: string } | undefined) => !!m && compareAmounts(m.amount, '0.00') > 0;
  return moved(invoice.amountCredited) || moved(invoice.amountPaid);
}
