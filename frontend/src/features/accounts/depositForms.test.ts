import { describe, expect, it } from 'vitest';
import { instructionSchema, payeeSchema, toInstructionBody } from './depositForms';

describe('the maturity instruction form', () => {
  it('reinvesting needs a term, and sends it as a number', () => {
    expect(instructionSchema.safeParse({ action: 'REINVEST', termMonths: '', payeeRef: '' }).success).toBe(false);
    expect(toInstructionBody({ action: 'REINVEST', termMonths: '6', payeeRef: '' })).toEqual({ action: 'REINVEST', termMonths: 6 });
  });

  it('paying out sends the payee only when one is typed', () => {
    expect(toInstructionBody({ action: 'PAY_OUT', termMonths: '', payeeRef: '' })).toEqual({ action: 'PAY_OUT' });
    expect(toInstructionBody({ action: 'PAY_OUT', termMonths: '', payeeRef: '+255700000001' })).toEqual({
      action: 'PAY_OUT',
      payeeRef: '+255700000001',
    });
  });

  it('a payout to a waiting deposit needs a payee', () => {
    expect(payeeSchema.safeParse({ payeeRef: '  ' }).success).toBe(false);
  });
});
