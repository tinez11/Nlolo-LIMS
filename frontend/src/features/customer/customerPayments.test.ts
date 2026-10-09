import { describe, expect, it } from 'vitest';
import type { InvoiceView } from '@/api/types';
import { isMobileNumber, owed, payableNow } from './customerPayments';

const invoice = (id: string, dueDate: string, status: NonNullable<InvoiceView['status']>, over: Partial<InvoiceView> = {}): InvoiceView => ({
  invoiceId: id, policyNumber: 'POL-1', dueDate, status, amount: { amount: '10000.00', currencyCode: 'TZS' }, ...over,
});

describe('customer payments', () => {
  it('offers what is late and only the next premium coming up', () => {
    const rows = [
      invoice('paid', '2026-08-01', 'PAID'),
      invoice('grace', '2026-09-01', 'IN_GRACE'),
      invoice('next', '2026-10-01', 'DUE'),
      invoice('later', '2026-11-01', 'DUE'),
      invoice('waived', '2026-07-01', 'WAIVED'),
    ];
    expect(payableNow(rows).map((i) => i.invoiceId)).toEqual(['grace', 'next']);
  });

  it('asks for the balance still due on a part-paid premium', () => {
    expect(owed(invoice('p', '2026-09-01', 'PARTIALLY_PAID', { balanceDue: { amount: '4000.00', currencyCode: 'TZS' } })))
      .toEqual({ amount: '4000.00', currency: 'TZS' });
  });

  it('accepts a Tanzanian mobile number in the usual shapes', () => {
    expect(isMobileNumber('0754 123 456')).toBe(true);
    expect(isMobileNumber('+255754123456')).toBe(true);
    expect(isMobileNumber('255654123456')).toBe(true);
    expect(isMobileNumber('12345')).toBe(false);
  });
});
