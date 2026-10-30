import { describe, expect, it } from 'vitest';
import type { CustomerProduct } from '@/api/portal';
import { amountChoices, beneficiaryProblem, needsDetail, progressOf, quoteRequest, stepsFor, yearChoices } from './selfService';

const terms = { minTermYears: null, maxTermYears: null, minAmount: null, maxAmount: null, depositTermMonths: [], funeralPlans: [], funeralRoles: [] };
const product = (kind: CustomerProduct['kind'], over: Partial<CustomerProduct['terms']> = {}) => ({
  productId: 'p', productName: 'P', category: 'TERM_LIFE', currency: 'TZS', summary: null, benefits: [], quotable: true,
  kind, online: true, terms: { ...terms, ...over }, questions: [],
}) as CustomerProduct;

describe('the questions a product asks', () => {
  it('asks each kind its own', () => {
    expect(stepsFor(product('PROTECTION', { minTermYears: 5, maxTermYears: 30 }))).toEqual(['amount', 'years', 'frequency']);
    expect(stepsFor(product('PROTECTION'))).toEqual(['amount', 'frequency']);
    expect(stepsFor(product('DEPOSIT'))).toEqual(['amount', 'term']);
    expect(stepsFor(product('FUNERAL'))).toEqual(['plan', 'family', 'frequency']);
  });

  it('asks a savings account how often only when the customer saves regularly', () => {
    expect(stepsFor(product('SAVINGS_ACCOUNT'))).toEqual(['saveHow', 'amount']);
    expect(stepsFor(product('SAVINGS_ACCOUNT'), { saveHow: 'REGULAR' })).toEqual(['saveHow', 'amount', 'frequency']);
    expect(quoteRequest(product('SAVINGS_ACCOUNT'), { saveHow: 'ONCE', amount: 100_000, frequency: 'MONTHLY' }))
      .toEqual({ amount: 100_000, frequency: null, termYears: null });
    expect(amountChoices(product('SAVINGS_ACCOUNT', { minAmount: 50_000 }))).toEqual([50_000, 100_000, 500_000, 1_000_000]);
  });

  it('offers amounts and years inside what the version allows', () => {
    expect(amountChoices(product('PROTECTION', { minAmount: 6_000_000, maxAmount: 30_000_000 }))).toEqual([10_000_000, 20_000_000]);
    expect(yearChoices(product('PROTECTION', { minTermYears: 3, maxTermYears: 12 }))).toEqual([5, 10]);
    expect(yearChoices(product('PROTECTION', { minTermYears: 2, maxTermYears: 4 }))).toEqual([2, 4]);
  });

  it('asks the server in each kind\'s own words', () => {
    expect(quoteRequest(product('DEPOSIT'), { amount: 1_000_000, termMonths: 6 })).toEqual({ amount: 1_000_000, termMonths: 6 });
    expect(quoteRequest(product('FUNERAL'), { planCode: 'B', frequency: 'MONTHLY',
      family: [{ role: 'CHILD', name: ' Neema ', dateOfBirth: '2020-01-01', student: false }] }))
      .toEqual({ planCode: 'B', frequency: 'MONTHLY', family: [{ role: 'CHILD', name: 'Neema', dateOfBirth: '2020-01-01', student: false }] });
  });
});

describe('an application', () => {
  it('stops its line at the decision when it is declined, and waits on the customer when sent back', () => {
    expect(progressOf({ stage: 'REVIEWING' })).toEqual({ at: 1, tone: 'go' });
    expect(progressOf({ stage: 'NEEDS_YOU' })).toEqual({ at: 1, tone: 'wait' });
    expect(progressOf({ stage: 'ISSUED' })).toEqual({ at: 3, tone: 'go' });
    expect(progressOf({ stage: 'DECLINED' })).toEqual({ at: 2, tone: 'stop' });
  });

  it('asks for a few words where an answer worries, and shares that make 100', () => {
    expect(needsDetail('GOOD_HEALTH', 'NO')).toBe(true);
    expect(needsDetail('GOOD_HEALTH', 'YES')).toBe(false);
    expect(needsDetail('TOBACCO_12M', 'YES')).toBe(true);
    expect(beneficiaryProblem([{ name: 'A', relationship: '', phone: '', share: '60' }, { name: '', relationship: '', phone: '', share: '' }]))
      .toEqual({ total: 60 });
    expect(beneficiaryProblem([{ name: 'A', relationship: '', phone: '', share: '60' }, { name: 'B', relationship: '', phone: '', share: '40' }]))
      .toBeNull();
  });
});
