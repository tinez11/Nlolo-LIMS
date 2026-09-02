import { describe, expect, it } from 'vitest';
import {
  blankLoanRepaymentForm,
  loanRepaymentFormSchema,
  toApiRequest,
} from './loanRepaymentForm';

describe('loanRepaymentForm', () => {
  it('accepts an amount and a payment reference', () => {
    expect(
      loanRepaymentFormSchema.safeParse({ amount: '200000', paymentReference: 'PAY-REF-01' })
        .success,
    ).toBe(true);
  });

  it('accepts a part-shilling repayment to two decimals', () => {
    expect(
      loanRepaymentFormSchema.safeParse({ amount: '328.77', paymentReference: 'R' }).success,
    ).toBe(true);
  });

  it.each(['328.777', '0', '-5', 'abc', ''])('rejects %s as an amount', (amount) => {
    expect(loanRepaymentFormSchema.safeParse({ amount, paymentReference: 'R' }).success).toBe(
      false,
    );
  });

  it('requires a payment reference, since it is what ties this to money received', () => {
    expect(
      loanRepaymentFormSchema.safeParse({ amount: '100', paymentReference: '  ' }).success,
    ).toBe(false);
  });

  it('permits an overpayment, because the platform does', () => {
    // The balance accrues interest daily, so any client-side ceiling would be
    // stale by the time it was submitted. The backend folds the ledger to zero
    // or below and settles the loan -- rejecting locally would invent a rule.
    expect(
      loanRepaymentFormSchema.safeParse({ amount: '99999999', paymentReference: 'R' }).success,
    ).toBe(true);
  });

  it('trims and forwards the amount as a decimal string', () => {
    const request = toApiRequest({ amount: ' 200000.50 ', paymentReference: ' PAY-1 ' });
    expect(request.amount).toEqual({ amount: '200000.50', currencyCode: 'TZS' });
    expect(request.paymentReference).toBe('PAY-1');
  });

  it('starts blank and a blank form does not validate', () => {
    expect(blankLoanRepaymentForm()).toEqual({ amount: '', paymentReference: '' });
    expect(loanRepaymentFormSchema.safeParse(blankLoanRepaymentForm()).success).toBe(false);
  });
});
