import { describe, expect, it } from 'vitest';
import { compareAmounts, previewBenefit, type SchemeBasis } from './groupBenefitPreview';

/**
 * The worked examples here are deliberately the SAME ones
 * `GroupBenefitCalculatorTest` uses on the Java side. That is the whole point of
 * having both: this module mirrors the server's rule so a scheme administrator can
 * see the free cover consequence while typing, and the only defence against the
 * mirror drifting is that both sides answer identically to the same inputs.
 */

const flat: SchemeBasis = {
  benefitBasis: 'FLAT',
  currency: 'TZS',
  flatBenefitAmount: '5000000.00',
  fclAmount: null,
};

const salaried: SchemeBasis = {
  benefitBasis: 'SALARY_MULTIPLE',
  currency: 'TZS',
  salaryMultiple: 4,
  fclAmount: '100000000.00',
};

const graded: SchemeBasis = {
  benefitBasis: 'GRADED',
  currency: 'TZS',
  fclAmount: '30000000.00',
  gradeBenefits: { MANAGEMENT: '50000000.00', STAFF: '10000000.00' },
};

describe('previewBenefit', () => {
  it('covers a flat-scheme member for the scheme amount', () => {
    expect(previewBenefit(flat, {})).toEqual({
      benefit: { amount: '5000000.00', currencyCode: 'TZS' },
      covered: { amount: '5000000.00', currencyCode: 'TZS' },
      status: 'WITHIN_FCL',
      excess: null,
    });
  });

  it('multiplies salary by the scheme multiple', () => {
    const preview = previewBenefit(salaried, { salaryAmount: '5000000.00' });
    expect(preview?.benefit.amount).toBe('20000000.00');
    expect(preview?.covered.amount).toBe('20000000.00');
    expect(preview?.status).toBe('WITHIN_FCL');
  });

  /**
   * The case the whole preview exists for: 30m on 4x is 120m against a 100m
   * limit, so the member is covered for 100m NOW and the 20m excess waits on
   * evidence. Not zero, and not 120m.
   */
  it('caps a member above the free cover limit and names the excess', () => {
    const preview = previewBenefit(salaried, { salaryAmount: '30000000.00' });
    expect(preview).toEqual({
      benefit: { amount: '120000000.00', currencyCode: 'TZS' },
      covered: { amount: '100000000.00', currencyCode: 'TZS' },
      status: 'EVIDENCE_REQUIRED',
      excess: { amount: '20000000.00', currencyCode: 'TZS' },
    });
  });

  it('takes a graded member from the scheme grade table', () => {
    expect(previewBenefit(graded, { gradeCode: 'STAFF' })?.covered.amount).toBe('10000000.00');
    // MANAGEMENT is worth 50m against a 30m limit.
    const manager = previewBenefit(graded, { gradeCode: 'MANAGEMENT' });
    expect(manager?.covered.amount).toBe('30000000.00');
    expect(manager?.excess?.amount).toBe('20000000.00');
  });

  it('says nothing for a grade the scheme does not have', () => {
    expect(previewBenefit(graded, { gradeCode: 'DIRECTORS' })).toBeNull();
  });

  /**
   * A partially typed salary must render NOTHING. A zero here would read as a
   * real answer -- "this person is covered for TZS 0.00" -- which is both wrong
   * and alarming, and would flicker on every keystroke.
   */
  it('says nothing until the inputs are enough to answer', () => {
    expect(previewBenefit(salaried, {})).toBeNull();
    expect(previewBenefit(salaried, { salaryAmount: '' })).toBeNull();
    expect(previewBenefit(salaried, { salaryAmount: '30000.' })).toBeNull();
    expect(previewBenefit(graded, {})).toBeNull();
  });

  /**
   * No limit is not a limit of zero. The first would send nobody to
   * underwriting; the second would send everybody.
   */
  it('treats a scheme with no limit as covering everybody in full', () => {
    const noLimit = previewBenefit({ ...salaried, fclAmount: null }, { salaryAmount: '30000000.00' });
    expect(noLimit?.covered.amount).toBe('120000000.00');
    expect(noLimit?.status).toBe('WITHIN_FCL');
    expect(noLimit?.excess).toBeNull();
  });

  it('covers a member exactly ON the limit without asking for evidence', () => {
    // 25,000,000 x 4 = exactly the 100,000,000 limit. `<=`, not `<`.
    const preview = previewBenefit(salaried, { salaryAmount: '25000000.00' });
    expect(preview?.status).toBe('WITHIN_FCL');
    expect(preview?.covered.amount).toBe('100000000.00');
  });
});

/**
 * The arithmetic is in scaled BigInt rather than JS numbers, and these are the
 * cases that would prove a float wrong. `lib/money.ts` bans arithmetic on money
 * precisely because a double looks right until it does not.
 */
describe('exact decimal arithmetic', () => {
  it('rounds a fractional multiple HALF_UP to two places, like BigDecimal', () => {
    // 1,234,567.89 x 3.33 = 4,111,111.0737 -> 4,111,111.07
    const preview = previewBenefit(
      { ...salaried, salaryMultiple: 3.33, fclAmount: null },
      { salaryAmount: '1234567.89' },
    );
    expect(preview?.benefit.amount).toBe('4111111.07');
  });

  it('rounds a value sitting exactly on the half away from zero', () => {
    // 1.25 x 1.1 = 1.375 -> 1.38, not 1.37.
    const preview = previewBenefit(
      { benefitBasis: 'SALARY_MULTIPLE', currency: 'TZS', salaryMultiple: 1.1, fclAmount: null },
      { salaryAmount: '1.25' },
    );
    expect(preview?.benefit.amount).toBe('1.38');
  });

  it('stays exact past the range a double can represent', () => {
    // 9007199254740993 is the first integer a double cannot hold. A float-based
    // implementation returns ...94.00 here, which is the exact bug lib/money.ts
    // was written to prevent.
    const preview = previewBenefit(
      { benefitBasis: 'SALARY_MULTIPLE', currency: 'TZS', salaryMultiple: 1, fclAmount: null },
      { salaryAmount: '9007199254740993.99' },
    );
    expect(preview?.benefit.amount).toBe('9007199254740993.99');
  });

  it('compares amounts of differing scale correctly', () => {
    expect(compareAmounts('100.00', '100')).toBe(0);
    expect(compareAmounts('100.10', '100.9')).toBe(-1);
    expect(compareAmounts('9007199254740993.99', '9007199254740993.98')).toBe(1);
  });
});
