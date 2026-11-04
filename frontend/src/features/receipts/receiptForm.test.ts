import { describe, expect, it } from 'vitest';
import { blankReceiptForm, receiptFormSchema, toApiRequest } from './receiptForm';

const valid = blankReceiptForm({
  policyNumber: 'POL-1',
  invoiceId: 'inv-1',
  method: 'CHEQUE',
  reference: ' CHQ-731 ',
  amount: '120000',
  receivedOn: '2026-10-01',
});

describe('receipt form', () => {
  it('accepts a receipt with its invoice, method, reference, amount and day', () => {
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

  it('sends money as a decimal string in the invoice currency, and blanks as nothing', () => {
    expect(toApiRequest(valid, 'TZS')).toEqual({
      invoiceId: 'inv-1',
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

  // Money movement step 1 (2026-11-04): a deposit can repay a loan instead of paying an invoice.
  it('a loan deposit needs the loan, not an invoice', () => {
    const parsed = receiptFormSchema.safeParse({ ...valid, paysFor: 'LOAN', invoiceId: '', loanId: '' });
    expect(parsed.success).toBe(false);
    expect(parsed.error?.issues.map((i) => i.path[0])).toEqual(['loanId']);
  });

  it('a premium deposit needs the invoice', () => {
    const parsed = receiptFormSchema.safeParse({ ...valid, invoiceId: '' });
    expect(parsed.error?.issues.map((i) => i.path[0])).toEqual(['invoiceId']);
  });

  it('a loan deposit goes out with loanId and no invoiceId, whatever invoice was chosen before', () => {
    const body = toApiRequest({ ...valid, paysFor: 'LOAN', loanId: 'loan-1' }, 'TZS');
    expect(body.loanId).toBe('loan-1');
    expect(body.invoiceId).toBeNull();
  });
});
