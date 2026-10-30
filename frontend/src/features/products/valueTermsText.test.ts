import { describe, expect, it } from 'vitest';
import type { VersionValueTermsView } from '@/api/types';
import { bonusScaleRows, depositGrid, payoutRow, valueKindLabel } from './valueTermsText';

const NONE: VersionValueTermsView = { bonus: null, cashValue: null, payouts: null, account: null, depositRates: [] };
const account = (band: number | null) => ({ guaranteedRatePercent: 3, minimumBalance: 0, minRatePercent: band,
  maxRatePercent: band, charges: [], usesAccountCharges: false });

describe('valueKindLabel', () => {
  it('names the kind from what the version carries, not its category', () => {
    expect(valueKindLabel(NONE)).toBeNull();
    expect(valueKindLabel({ ...NONE, account: account(null) })).toBe('Savings account');
    expect(valueKindLabel({ ...NONE, account: account(5) })).toBe('Term account');
    expect(valueKindLabel({ ...NONE, account: account(null), depositRates: [{ minAmount: 1, termMonths: 12, ratePercent: 9 }] }))
      .toBe('Fixed deposit');
    expect(valueKindLabel({ ...NONE,
      cashValue: { basisReference: 'A', basisDate: '2026-01-01', paidUpBasis: 'TABLE', minYearsForValue: 2, rows: [] },
      bonus: { method: 'SIMPLE', paidUpParticipates: true, surrenderBasis: 'NONE', surrenderRows: [] } }))
      .toBe('Cash-value plan, with profits');
  });
});

describe('bonusScaleRows', () => {
  it('runs each row up to the next one, the last open-ended, with the share of the bonus', () => {
    expect(bonusScaleRows([{ fromCompletedYears: 5, perMille: 800 }, { fromCompletedYears: 0, perMille: 0 },
      { fromCompletedYears: 4, perMille: 400 }])).toEqual([
      ['0–3', '0', '0%'], ['4', '400', '40%'], ['5 or more', '800', '80%'],
    ]);
  });
});

describe('payoutRow', () => {
  it('says a survival benefit is a yearly amount over its years, paid at its frequency', () => {
    expect(payoutRow({ kind: 'SURVIVAL', fromPolicyYear: 5, toPolicyYear: 10, amountBasis: 'PERCENT_OF_SA',
      amountValue: 10, frequency: 'QUARTERLY' })).toEqual(['Survival benefit', 'Years 5–10', '10% of the sum assured a year', 'Every quarter']);
  });

  it('pays an account at maturity once and whole', () => {
    expect(payoutRow({ kind: 'MATURITY', fromPolicyYear: null, toPolicyYear: null, amountBasis: 'ACCOUNT_VALUE',
      amountValue: 100, frequency: null })).toEqual(['Maturity benefit', 'At maturity', 'The whole account', 'Once']);
  });
});

describe('depositGrid', () => {
  it('lays a deposit out by amount band and term', () => {
    const grid = depositGrid([
      { minAmount: 1000000, termMonths: 12, ratePercent: 9 }, { minAmount: 1000000, termMonths: 6, ratePercent: 4 },
      { minAmount: 5000000, termMonths: 12, ratePercent: 10 }, { minAmount: 5000000, termMonths: 6, ratePercent: 4.5 },
    ]);
    expect(grid.columns).toEqual(['Deposit of', '6 months', '1 year']);
    expect(grid.rows).toEqual([
      ['1,000,000 to under 5,000,000', '4%', '9%'],
      ['5,000,000 or more', '4.5%', '10%'],
    ]);
  });
});
