import { describe, expect, it } from 'vitest';
import { adjustmentSchema, topUpSchema, transferInSchema, withdrawalSchema } from './accountForms';

const issues = (result: { success: boolean; error?: { issues: { message: string }[] } }) =>
  result.success ? [] : result.error!.issues.map((i) => i.message);

describe('withdrawalSchema', () => {
  it('accepts the server’s amount shape', () => {
    expect(withdrawalSchema.safeParse({ amount: '40000.00', payeeRef: '+255700000001' }).success).toBe(true);
    expect(withdrawalSchema.safeParse({ amount: '40000', payeeRef: '+255700000001' }).success).toBe(true);
  });

  it('refuses three decimals, a minus, and zero -- each a 400 the server would give', () => {
    expect(issues(withdrawalSchema.safeParse({ amount: '12.345', payeeRef: 'x' }))).toContain(
      'An amount with at most two decimals, for example 25000.00',
    );
    expect(withdrawalSchema.safeParse({ amount: '-5.00', payeeRef: 'x' }).success).toBe(false);
    expect(issues(withdrawalSchema.safeParse({ amount: '0', payeeRef: 'x' }))).toContain('Must be more than zero');
  });

  it('needs a payee', () => {
    expect(issues(withdrawalSchema.safeParse({ amount: '1.00', payeeRef: '  ' }))).toContain('Who is the money paid to?');
  });
});

describe('topUpSchema and transferInSchema', () => {
  it('need a payer and a source scheme respectively', () => {
    expect(issues(topUpSchema.safeParse({ amount: '1.00', payerRef: '' }))).toContain('Who is the money collected from?');
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
