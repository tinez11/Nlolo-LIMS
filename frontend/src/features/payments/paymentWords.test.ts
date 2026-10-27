import { describe, expect, it } from 'vitest';
import type { PaymentRecord } from '@/api/payments';
import { methodWords, paymentLink, purposeWords } from './paymentWords';

const payment = (over: Partial<PaymentRecord>): PaymentRecord => ({
  id: 'p-1',
  direction: 'OUT',
  purpose: 'WITHDRAWAL_PAYOUT',
  method: 'MOBILE_MONEY',
  status: 'FAILED',
  amount: { amount: '100.00', currencyCode: 'TZS' },
  counterparty: '+255700000001',
  sourceRef: 'src-1',
  createdAt: '2026-10-27T08:00:00Z',
  ...over,
});

describe('payment words', () => {
  it('says what a payment is for and how it moved, in plain words', () => {
    expect(purposeWords('CLAIM_SETTLEMENT')).toBe('Claim settlement');
    expect(methodWords('EFT')).toBe('Bank transfer');
    expect(purposeWords('SOMETHING_NEW')).toBe('Something new');
  });

  it('opens the claim a settlement pays and the instalment a benefit pays', () => {
    expect(paymentLink(payment({ purpose: 'CLAIM_SETTLEMENT', sourceRef: 'c-9' }))).toBe('/staff/claims/c-9');
    expect(paymentLink(payment({ purpose: 'SURVIVAL_BENEFIT_PAYOUT', sourceRef: 'i-3' }))).toBe('/staff/payouts/i-3');
  });

  it('otherwise opens the policy, and nothing where none was recorded', () => {
    expect(paymentLink(payment({ policyNumber: 'POL-7' }))).toBe('/staff/policies/POL-7');
    expect(paymentLink(payment({ purpose: 'COMMISSION_PAYOUT', policyNumber: null }))).toBeNull();
  });
});
