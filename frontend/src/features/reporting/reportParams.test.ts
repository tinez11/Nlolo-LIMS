import { describe, expect, it } from 'vitest';
import type { ReportDefinition } from '@/api/reporting';
import { capRows, defaultFilters, formatCell, stateFromSearch, toParams, validateFilters } from './reportParams';

describe('capRows', () => {
  it('draws every row of a section within the limit', () => {
    expect(capRows([1, 2, 3], 5)).toEqual({ shown: [1, 2, 3], hidden: 0 });
    expect(capRows([1, 2, 3], 3)).toEqual({ shown: [1, 2, 3], hidden: 0 });
  });

  it('draws the first rows of a longer one and counts what it left for the download', () => {
    expect(capRows([1, 2, 3, 4, 5], 2)).toEqual({ shown: [1, 2], hidden: 3 });
  });
});

const definition = (dateFilterKind: ReportDefinition['dateFilterKind'], comparable = false): ReportDefinition => ({
  key: 'k',
  title: 'T',
  description: 'D',
  category: 'MANAGEMENT',
  dateFilterKind,
  groupByOptions: ['PRODUCT', 'AGENT'],
  comparable,
  requiresAccount: false,
});

const TODAY = new Date(2026, 9, 11); // 11 October 2026, local time

describe('accounting report filters', () => {
  const ledger = (kind: ReportDefinition['dateFilterKind'], comparable = false): ReportDefinition => ({
    ...definition(kind, comparable),
    key: 'trial-balance',
    category: 'ACCOUNTING',
    groupByOptions: [],
  });

  it('opens a single-period report on the current month and a span on the year to date', () => {
    expect(defaultFilters(ledger('MONTH'), TODAY)).toMatchObject({ monthFrom: '2026-10', monthTo: '', asOf: '' });
    expect(defaultFilters(ledger('MONTH_RANGE'), TODAY)).toMatchObject({ monthFrom: '2026-01', monthTo: '2026-10' });
  });

  it('sends the month and never a day: the ledger has no posting date to match one', () => {
    const state = { ...defaultFilters(ledger('MONTH'), TODAY), asOf: '2026-10-11' };
    const params = toParams(ledger('MONTH'), state);
    expect(params.monthFrom).toBe('2026-10');
    expect(params.asOf).toBeUndefined();
    expect(params.monthTo).toBeUndefined();
  });

  it('sends both ends and the account for a ledger range', () => {
    const state = { ...defaultFilters(ledger('MONTH_RANGE'), TODAY), monthFrom: '2026-04', monthTo: '2026-09', accountCode: '1100' };
    const params = toParams(ledger('MONTH_RANGE'), state);
    expect(params).toMatchObject({ monthFrom: '2026-04', monthTo: '2026-09', accountCode: '1100' });
  });

  it('sends a comparison month only where the report is comparable', () => {
    const state = { ...defaultFilters(ledger('MONTH'), TODAY), compareAsOf: '2026-06-30' };
    expect(toParams(ledger('MONTH', true), state).compareAsOf).toBe('2026-06-30');
    expect(toParams(ledger('MONTH', false), state).compareAsOf).toBeUndefined();
  });

  it('refuses a range running backwards before sending it', () => {
    const base = defaultFilters(ledger('MONTH_RANGE'), TODAY);
    expect(validateFilters(ledger('MONTH_RANGE'), { ...base, monthFrom: '2026-09', monthTo: '2026-04' }))
      .toMatch(/must not be after/);
    expect(validateFilters(ledger('MONTH_RANGE'), { ...base, monthFrom: '2026-04', monthTo: '2026-09' })).toBeNull();
  });

  it('refuses a single-period report with no period', () => {
    const base = defaultFilters(ledger('MONTH'), TODAY);
    expect(validateFilters(ledger('MONTH'), { ...base, monthFrom: '' })).toMatch(/Choose a period/);
  });

  /**
   * A report ABOUT one account asks for it here. Before this the console submitted without one and rendered
   * the server's refusal where the report should be -- a whole-region failure with a "Try again" that would
   * fail identically, over a field nobody had filled in yet.
   */
  it('asks for the account on a report that is about one, and not on reports that are not', () => {
    const generalLedger = { ...ledger('MONTH_RANGE'), key: 'general-ledger', requiresAccount: true };
    const base = defaultFilters(generalLedger, TODAY);

    expect(validateFilters(generalLedger, base)).toMatch(/Choose an account/);
    expect(validateFilters(generalLedger, { ...base, accountCode: '1110' })).toBeNull();
    // The income statement spans the same periods and shows every account: it must not demand one.
    expect(validateFilters(ledger('MONTH_RANGE'), defaultFilters(ledger('MONTH_RANGE'), TODAY))).toBeNull();
  });

  it('restores a month filter from the URL so a reload runs the same report', () => {
    const search = new URLSearchParams({ monthFrom: '2026-03', accountCode: '1100' });
    expect(stateFromSearch(ledger('MONTH'), search, TODAY)).toMatchObject({
      monthFrom: '2026-03',
      accountCode: '1100',
    });
  });
});

describe('report filters', () => {
  it('defaults each date kind to a sensible window ending today', () => {
    expect(defaultFilters(definition('AS_OF'), TODAY).asOf).toBe('2026-10-11');
    expect(defaultFilters(definition('PERIOD'), TODAY)).toMatchObject({ from: '2026-10-01', to: '2026-10-11' });
    // Two years back, so months 13 and 25 have both been reached.
    expect(defaultFilters(definition('COHORT'), TODAY)).toMatchObject({ cohortFrom: '2024-09', cohortTo: '2024-09' });
    expect(defaultFilters(definition('AS_OF_AND_PERIOD'), TODAY)).toMatchObject({
      asOf: '2026-10-11',
      from: '2026-10-01',
      to: '2026-10-11',
    });
  });

  it('sends only the parameters the report takes', () => {
    const state = { ...defaultFilters(definition('PERIOD'), TODAY), asOf: '2026-01-01', groupBy: 'AGENT', clientType: '' };
    expect(toParams(definition('PERIOD'), state)).toEqual({ from: '2026-10-01', to: '2026-10-11', groupBy: 'AGENT' });
    const compared = { ...defaultFilters(definition('AS_OF', true), TODAY), compareAsOf: '2026-09-30' };
    expect(toParams(definition('AS_OF', true), compared)).toEqual({ asOf: '2026-10-11', compareAsOf: '2026-09-30' });
    expect(toParams(definition('AS_OF', false), compared)).toEqual({ asOf: '2026-10-11' });
  });

  it('reads filters back from the address bar, falling back to defaults', () => {
    const state = stateFromSearch(definition('PERIOD'), new URLSearchParams('from=2026-01-01&groupBy=AGENT'), TODAY);
    expect(state).toMatchObject({ from: '2026-01-01', to: '2026-10-11', groupBy: 'AGENT' });
  });

  it('narrows any report to one product, and sends nothing when none is chosen', () => {
    const chosen = stateFromSearch(definition('AS_OF'), new URLSearchParams('asOf=2026-10-11&productId=p-1'), TODAY);
    expect(chosen.productId).toBe('p-1');
    expect(toParams(definition('AS_OF'), chosen)).toEqual({ asOf: '2026-10-11', productId: 'p-1' });
    expect(toParams(definition('AS_OF'), defaultFilters(definition('AS_OF'), TODAY))).not.toHaveProperty('productId');
  });

  it('refuses a missing date or a reversed range', () => {
    expect(validateFilters(definition('AS_OF'), { ...defaultFilters(definition('AS_OF'), TODAY), asOf: '' })).toMatch(/As of/);
    expect(
      validateFilters(definition('PERIOD'), { ...defaultFilters(definition('PERIOD'), TODAY), from: '2026-12-01' }),
    ).toMatch(/From/);
    expect(validateFilters(definition('PERIOD'), defaultFilters(definition('PERIOD'), TODAY))).toBeNull();
  });

  it('formats figures for reading without changing them', () => {
    expect(formatCell('MONEY', '1234567.5')).toBe('1,234,567.50');
    expect(formatCell('COUNT', '12345')).toBe('12,345');
    expect(formatCell('PERCENT', '66.67')).toBe('66.67%');
    expect(formatCell('PERCENT', '')).toBe('—');
    expect(formatCell('TEXT', 'Family Funeral')).toBe('Family Funeral');
    expect(formatCell('MONEY', 'not-a-number')).toBe('not-a-number');
  });
});
