import { describe, expect, it } from 'vitest';
import { blankReceiptForm, receiptFormSchema, toApiRequest } from './receiptForm';

const valid = blankReceiptForm({
  policyNumber: 'pol-1',
  method: 'CHEQUE',
  reference: ' CHQ-731 ',
  amount: '120000',
  receivedOn: '2026-10-01',
});

describe('receipt form', () => {
  it('accepts a deposit with its policy, method, reference, amount and day -- no invoice', () => {
    expect(receiptFormSchema.safeParse(valid).success).toBe(true);
  });

  it('refuses one with no reference, since the reference is what stops double recording', () => {
    const result = receiptFormSchema.safeParse({ ...valid, reference: '  ' });
    expect(result.success).toBe(false);
  });

  it('refuses a day after today and an amount of nothing', () => {
    expect(receiptFormSchema.safeParse({ ...valid, receivedOn: '2999-01-01' }).success).toBe(false);
    expect(receiptFormSchema.safeParse({ ...valid, amount: '0' }).success).toBe(false);
  });

  // Money movement step 5 (design 7.2): a premium deposit names the policy, and its balance pays what is due.
  it('sends a premium deposit onto the policy, money as a decimal string, and blanks as nothing', () => {
    expect(toApiRequest(valid, 'TZS')).toEqual({
      policyNumber: 'POL-1',
      loanId: null,
      method: 'CHEQUE',
      reference: 'CHQ-731',
      amount: { amount: '120000.00', currencyCode: 'TZS' },
      receivedOn: '2026-10-01',
      payerRef: null,
      note: null,
      fieldReceiptId: null,
    });
  });

  // Money movement step 1 (2026-11-04): a deposit can repay a loan instead.
  it('a loan deposit needs the loan', () => {
    const parsed = receiptFormSchema.safeParse({ ...valid, paysFor: 'LOAN', loanId: '' });
    expect(parsed.success).toBe(false);
    expect(parsed.error?.issues.map((i) => i.path[0])).toEqual(['loanId']);
  });

  // 2026-11-07, the user: one Deposit page; the method decides what is asked.
  it('mobile money asks only for the phone -- and, but for a premium, the amount -- never a reference or a day', () => {
    const mobile = { ...valid, method: 'MOBILE_MONEY' as const, reference: '', receivedOn: '', amount: '' };
    const paths = (v: typeof mobile) => receiptFormSchema.safeParse(v).error?.issues.map((i) => i.path[0]) ?? [];
    expect(paths(mobile)).toEqual(['payerRef']);
    expect(receiptFormSchema.safeParse({ ...mobile, payerRef: '+255700000002' }).success).toBe(true);
    expect(paths({ ...mobile, paysFor: 'SAVINGS', payerRef: '+255700000002' })).toEqual(['amount']);
  });

  it('money received still needs its reference and day, savings included', () => {
    const parsed = receiptFormSchema.safeParse({ ...valid, paysFor: 'SAVINGS', reference: '', receivedOn: '' });
    expect(parsed.error?.issues.map((i) => i.path[0])).toEqual(['reference', 'receivedOn']);
  });

  it('a loan deposit goes out with loanId and no policy balance', () => {
    const body = toApiRequest({ ...valid, paysFor: 'LOAN', loanId: 'loan-1' }, 'TZS');
    expect(body.loanId).toBe('loan-1');
    expect(body.policyNumber).toBeNull();
  });
});
