import { describe, expect, it } from 'vitest';
import type { CreditLifeAgreement } from '@/api/types';
import {
  agreementFormSchema,
  agreementOn,
  defaultAgreementValues,
  isPositive,
  pricingRuleInWords,
  toAgreementInput,
  trimZeros,
} from './creditLifeAgreementForm';

const year2026: CreditLifeAgreement = {
  agreementId: 'a1',
  policyNumber: 'CL-1',
  validFrom: '2026-01-01',
  validTo: '2026-12-31',
  premiumRatePercent: '0.6000',
  premiumBasis: 'WHOLE_YEARS_ROUNDED_UP',
  minimumPremium: '2000.00',
  minAge: 21,
  maxAge: 75,
  minTermMonths: 12,
  maxTermMonths: 120,
  commissionPercent: '10.00',
  coverIncludesInterest: true,
  createdAt: '2026-01-01T00:00:00Z',
  createdBy: 'staff',
};

describe('the next year', () => {
  it('starts the day after the last one ends and keeps its terms', () => {
    const v = defaultAgreementValues(year2026, '2026-10-30');
    expect(v.validFrom).toBe('2027-01-01');
    expect(v.validTo).toBe('2027-12-31');
    expect(v.premiumRatePercent).toBe('0.6000');
    expect(v.minAge).toBe('21');
    expect(v.maxAge).toBe('75');
    expect(v.coverIncludesInterest).toBe(true);
  });

  it('a first agreement starts today, runs a year, takes adults, and takes a loan of any length from a month', () => {
    // A four-month loan is charged as a year by the rule, not refused (the lender's own file, 2026-11-01).
    const v = defaultAgreementValues(null, '2026-10-30');
    expect(v.validFrom).toBe('2026-10-30');
    expect(v.validTo).toBe('2027-10-29');
    expect(v.minAge).toBe('18');
    expect(v.minTermMonths).toBe('1');
    expect(v.premiumBasis).toBe('WHOLE_YEARS_ROUNDED_UP');
  });
});

describe('the form', () => {
  const valid = { ...defaultAgreementValues(year2026, '2026-10-30') };

  it('accepts a sound year and sends its decimals as strings', () => {
    expect(agreementFormSchema().safeParse(valid).success).toBe(true);
    const input = toAgreementInput({ ...valid, premiumRatePercent: ' 0.6 ', commissionPercent: '' });
    expect(input.premiumRatePercent).toBe('0.6');
    expect(input).not.toHaveProperty('commissionPercent');
    expect(input.minAge).toBe(21);
    expect(input.maxAge).toBe(75);
  });

  it('refuses a youngest borrower under 18, and an oldest younger than the youngest', () => {
    expect(agreementFormSchema().safeParse({ ...valid, minAge: '17' }).success).toBe(false);
    const r = agreementFormSchema().safeParse({ ...valid, minAge: '40', maxAge: '30' });
    expect(r.success ? [] : r.error.issues.map((i) => i.path.join('.'))).toContain('maxAge');
  });

  it('refuses commission above the TIRA cap', () => {
    const r = agreementFormSchema().safeParse({ ...valid, commissionPercent: '15.5' });
    expect(r.success).toBe(false);
  });

  it('refuses a year that ends before it starts, and a longest loan under the shortest', () => {
    const r = agreementFormSchema().safeParse({ ...valid, validTo: '2026-12-01', maxTermMonths: '6' });
    expect(r.success).toBe(false);
    const paths = r.success ? [] : r.error.issues.map((i) => i.path.join('.'));
    expect(paths).toEqual(expect.arrayContaining(['validTo', 'maxTermMonths']));
  });
});

describe('reading an agreement', () => {
  it('finds the year covering a day, and none outside every year', () => {
    expect(agreementOn([year2026], '2026-06-15')?.agreementId).toBe('a1');
    expect(agreementOn([year2026], '2027-01-01')).toBeNull();
  });

  it('says the price the way the template does, with the server scale trimmed', () => {
    expect(pricingRuleInWords(year2026)).toBe(
      'Loan amount × 0.6% for each year the loan runs; part of a year counts as a full year',
    );
    expect(trimZeros('10.00')).toBe('10');
    expect(trimZeros('12.50')).toBe('12.5');
    expect(trimZeros('2000')).toBe('2000');
  });

  it('reads zero from the digits, never a double', () => {
    expect(isPositive('0.00')).toBe(false);
    expect(isPositive('0.0001')).toBe(true);
    expect(isPositive('-5.00')).toBe(false);
  });
});
