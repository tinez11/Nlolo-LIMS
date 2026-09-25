import { describe, expect, it } from 'vitest';
import { hasMovement, owesSomething } from './invoiceReconciliation';

const money = (amount: string) => ({ amount, currencyCode: 'TZS' });

describe('owesSomething', () => {
  it('offers a payment request while a balance remains -- 13,800 charged, 4,200 credited', () => {
    expect(owesSomething({ status: 'DUE', balanceDue: money('9600.00') })).toBe(true);
  });

  it('offers none once payments and credits cover the charge', () => {
    // Asking would request money already given back, and the backend refuses it.
    expect(owesSomething({ status: 'PAID', balanceDue: money('0.00') })).toBe(false);
  });

  it('offers none on a waived invoice', () => {
    expect(owesSomething({ status: 'WAIVED', balanceDue: money('500.00') })).toBe(false);
  });

  it('leaves the backend to decide when the balance is unknown', () => {
    expect(owesSomething({ status: 'DUE' })).toBe(true);
  });
});

describe('hasMovement', () => {
  it('is true once anything was credited or paid, so the breakdown is shown', () => {
    expect(hasMovement({ amountCredited: money('4200.00'), amountPaid: money('0.00') })).toBe(true);
    expect(hasMovement({ amountCredited: money('0.00'), amountPaid: money('100.00') })).toBe(true);
  });

  it('is false for an untouched invoice, which owes exactly what it says', () => {
    expect(hasMovement({ amountCredited: money('0.00'), amountPaid: money('0.00') })).toBe(false);
  });
});
