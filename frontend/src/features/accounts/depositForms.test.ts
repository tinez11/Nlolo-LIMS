import { describe, expect, it } from 'vitest';
import { BLANK_ROUTE, payoutRouteSchema } from '@/features/payments/payoutRoute';
import { instructionSchema, toInstructionBody } from './depositForms';

const blank = { termMonths: '', ...BLANK_ROUTE };

describe('the maturity instruction form', () => {
  it('reinvesting needs a term, and sends it as a number', () => {
    expect(instructionSchema.safeParse({ ...blank, action: 'REINVEST' }).success).toBe(false);
    expect(toInstructionBody({ ...blank, action: 'REINVEST', termMonths: '6' })).toEqual({ action: 'REINVEST', termMonths: 6 });
  });

  it('paying out by mobile money sends the number only when one is typed', () => {
    expect(instructionSchema.safeParse({ ...blank, action: 'PAY_OUT' }).success).toBe(true);
    expect(toInstructionBody({ ...blank, action: 'PAY_OUT' })).toEqual({ action: 'PAY_OUT' });
    expect(toInstructionBody({ ...blank, action: 'PAY_OUT', payeeRef: '+255700000001' })).toEqual({
      action: 'PAY_OUT',
      method: 'MOBILE_MONEY',
      payeeRef: '+255700000001',
    });
  });

  it('paying out by bank transfer names the account (2026-10-28)', () => {
    const eft = { ...blank, action: 'PAY_OUT' as const, method: 'EFT' as const };
    expect(instructionSchema.safeParse(eft).success).toBe(false);
    const named = { ...eft, payeeBank: 'CRDB Bank', payeeAccountName: 'Amina Owner', payeeAccountNumber: '0150123' };
    expect(instructionSchema.safeParse(named).success).toBe(true);
    expect(toInstructionBody(named)).toEqual({
      action: 'PAY_OUT',
      method: 'EFT',
      payeeBank: 'CRDB Bank',
      payeeAccountName: 'Amina Owner',
      payeeAccountNumber: '0150123',
    });
  });

  it('a payout to a waiting deposit needs a payee', () => {
    expect(payoutRouteSchema.safeParse({ ...BLANK_ROUTE, payeeRef: '  ' }).success).toBe(false);
  });
});
