import { describe, expect, it } from 'vitest';
import type { PolicyUnitsView } from '@/api/types';
import { needsCashValueTerms, policyValueLine } from './policyValue';

const money = (amount: string) => ({ amount, currencyCode: 'TZS' });
const policy = (productCategory: string, portfolioCode: string, cash = '0.00') =>
  ({ productCategory, portfolioCode, cashValue: money(cash) }) as Parameters<typeof policyValueLine>[0]['policy'];

/**
 * The policy summary's value line for every kind of policy and scheme in the dev data (2026-10-19). It used to say
 * "pure protection" for anything it did not recognise, and "nothing until the first two or three years" for an
 * endowment whose version has no cash-value table at all.
 */
describe('policyValueLine', () => {
  it.each([
    ['TERM_LIFE', 'TERM'],
    ['CREDIT_LIFE', 'CRL'],
    ['GROUP_LIFE', 'GRPL'],
    ['FUNERAL', 'FUN'],
  ])('%s is pure protection', (category, portfolio) => {
    expect(policyValueLine({ policy: policy(category, portfolio), isStaff: true }).note).toContain('pure protection');
  });

  it("shows a unit-linked policy's fund value, never pure protection", () => {
    const units = { totalValue: '91750.00', currency: 'TZS', pending: [], holdings: [] } as unknown as PolicyUnitsView;
    const line = policyValueLine({ policy: policy('UNIT_LINKED', 'ULIP'), isStaff: true, units });
    expect(line.label).toBe('Fund value');
    expect(line.value).toBe('TZS 91,750.00');
  });

  it('says a unit-linked policy is not invested yet, with a count of what waits -- not a sum', () => {
    const units = { totalValue: null, pending: [{}, {}], holdings: [] } as unknown as PolicyUnitsView;
    const line = policyValueLine({ policy: policy('UNIT_LINKED', 'ULIP'), isStaff: true, units });
    expect(line.value).toBe('Not invested yet');
    expect(line.note).toContain('2 purchases waiting');
  });

  it.each(['SAV', 'DEP', 'PEN', 'DANN'])('%s is an account balance, for agents too', (portfolio) => {
    const line = policyValueLine({ policy: policy('ENDOWMENT', portfolio, '295008.33'), isStaff: false });
    expect(line.label).toBe('Account value');
    expect(line.value).toBe('TZS 295,008.33');
    expect(line.note).not.toContain('cash-value table');
    expect(line.note).not.toContain('Account tab');
  });

  it('a vested pension says its account bought the income, not a 0.00 balance', () => {
    const line = policyValueLine({ policy: policy('ANNUITY', 'PEN'), isStaff: true, vested: true });
    expect(line.value).toBe('Paid into the pension');
    expect(line.note).toContain('Annuity tab');
  });

  it('an immediate annuity has no cash value, and is not called protection', () => {
    const line = policyValueLine({ policy: policy('ANNUITY', 'IANN'), isStaff: true });
    expect(line.value).toBe('None');
    expect(line.note).not.toContain('pure protection');
  });

  it('an endowment with no cash-value table says it never has a value, not "not yet"', () => {
    const line = policyValueLine({ policy: policy('EDUCATION_SAVINGS', 'END'), isStaff: true,
      cashValueTerms: { hasTable: false, minYearsForValue: null, basisReference: null } });
    expect(line.value).toBe('None');
    expect(line.note).toContain('no cash-value table');
  });

  it('an endowment with a table says from which policy year it pays', () => {
    const line = policyValueLine({ policy: policy('ENDOWMENT', 'MB'), isStaff: true,
      cashValueTerms: { hasTable: true, minYearsForValue: 3, basisReference: 'CV-2026' } });
    expect(line.value).toBe('TZS 0.00');
    expect(line.note).toContain('Nothing before policy year 3');
  });

  it('asks for the cash-value terms only where a table is possible', () => {
    expect(needsCashValueTerms({ productCategory: 'ENDOWMENT', portfolioCode: 'END' })).toBe(true);
    expect(needsCashValueTerms({ productCategory: 'WHOLE_LIFE', portfolioCode: 'WL' })).toBe(true);
    expect(needsCashValueTerms({ productCategory: 'ENDOWMENT', portfolioCode: 'SAV' })).toBe(false);
    expect(needsCashValueTerms({ productCategory: 'TERM_LIFE', portfolioCode: 'TERM' })).toBe(false);
  });
});
