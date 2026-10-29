import { describe, expect, it } from 'vitest';
import { BLANK_ROUTE } from '@/features/payments/payoutRoute';
import { adjustmentSchema, topUpSchema, transferInSchema, withdrawalSchema } from './accountForms';

const issues = (result: { success: boolean; error?: { issues: { message: string }[] } }) =>
  result.success ? [] : result.error!.issues.map((i) => i.message);

describe('withdrawalSchema', () => {
  const mm = (amount: string, payeeRef = '+255700000001') => ({ ...BLANK_ROUTE, amount, payeeRef });

  it('accepts the server’s amount shape', () => {
    expect(withdrawalSchema.safeParse(mm('40000.00')).success).toBe(true);
    expect(withdrawalSchema.safeParse(mm('40000')).success).toBe(true);
  });

  it('refuses three decimals, a minus, and zero -- each a 400 the server would give', () => {
    expect(issues(withdrawalSchema.safeParse(mm('12.345')))).toContain(
      'An amount with at most two decimals, for example 25000.00',
    );
    expect(withdrawalSchema.safeParse(mm('-5.00')).success).toBe(false);
    expect(issues(withdrawalSchema.safeParse(mm('0')))).toContain('Must be more than zero');
  });

  it('needs the number on mobile money, the payee on a cheque', () => {
    expect(issues(withdrawalSchema.safeParse(mm('1.00', '  ')))).toContain('Give the mobile-money number to pay');
    expect(issues(withdrawalSchema.safeParse({ ...mm('1.00', ''), method: 'CHEQUE' }))).toContain(
      'Give the name the cheque is made out to',
    );
  });

  it('needs the bank, the account name and the account number on a bank transfer -- not the branch', () => {
    const eft = { ...mm('1.00', ''), method: 'EFT' as const };
    expect(issues(withdrawalSchema.safeParse(eft))).toEqual([
      'Give the bank to transfer to',
      'Give the name on the bank account',
      'Give the bank account number',
    ]);
    expect(
      withdrawalSchema.safeParse({ ...eft, payeeBank: 'CRDB Bank', payeeAccountName: 'Amina Owner', payeeAccountNumber: '0150123' })
        .success,
    ).toBe(true);
  });
});

describe('topUpSchema and transferInSchema', () => {
  it('need a payer and a source scheme respectively', () => {
    const topUp = { amount: '1.00', method: 'MOBILE_MONEY' as const, payerRef: '', reference: '', receivedOn: '' };
    expect(issues(topUpSchema.safeParse(topUp))).toContain('Who is the money collected from?');
    // Money already received names its reference and its day instead of a phone.
    expect(issues(topUpSchema.safeParse({ ...topUp, method: 'CASH' }))).toEqual([
      'Enter the reference, so the same money cannot be credited twice',
      'The day the money was received',
    ]);
    expect(topUpSchema.safeParse({ ...topUp, method: 'CASH', reference: 'RC-1', receivedOn: '2026-10-01' }).success).toBe(true);
    expect(issues(transferInSchema.safeParse({ amount: '1.00', sourceScheme: '', documentRef: '' }))).toContain(
      'Name the scheme the money came from',
    );
  });
});

describe('adjustmentSchema', () => {
  it('accepts a signed amount -- a correction may take money out', () => {
    expect(adjustmentSchema.safeParse({ amount: '-250.00', reason: 'Fee charged twice' }).success).toBe(true);
  });

  it('refuses zero and a missing reason, in the server’s words', () => {
    expect(issues(adjustmentSchema.safeParse({ amount: '0.00', reason: 'x' }))).toContain(
      'An adjustment must move the balance by something',
    );
    expect(issues(adjustmentSchema.safeParse({ amount: '1.00', reason: ' ' }))).toContain('An adjustment needs a reason');
  });
});
