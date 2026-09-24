import { describe, expect, it } from 'vitest';
import type { DisbursementView } from '@/api/types';
import { whatHappensNext } from './payoutNextStep';

const payout = (overrides: Partial<DisbursementView>): DisbursementView => ({
  disbursementId: 'd-1',
  method: 'EFT',
  status: 'AWAITING_EXECUTION',
  amount: { amount: '800000.00', currencyCode: 'TZS' },
  payeeRef: 'Lender Bank Ltd',
  reference: null,
  createdAt: '2026-09-24T10:00:00Z',
  executedAt: null,
  ...overrides,
});

describe('whatHappensNext', () => {
  it('names who a waiting bank transfer is waiting on, and where they act', () => {
    // The whole reason the panel exists: a credit-life claim sat here and nothing said so.
    expect(whatHappensNext(payout({}), false)).toMatch(/Finance .* Finance → Bank transfers/);
  });

  it('says a completed EFT was recorded by finance', () => {
    expect(
      whatHappensNext(payout({ status: 'COMPLETED', executedAt: '2026-09-25T09:00:00Z' }), false),
    ).toMatch(/^Paid\. Finance recorded the transfer/);
  });

  it('says a completed mobile-money payout was confirmed by the gateway', () => {
    expect(whatHappensNext(payout({ method: 'MOBILE_MONEY', status: 'COMPLETED' }), false)).toBe(
      'Paid. The gateway confirmed it.',
    );
  });

  it('marks an older failed attempt as replaced rather than as the current state', () => {
    expect(whatHappensNext(payout({ status: 'FAILED' }), true)).toMatch(/replaced it/);
  });
});
