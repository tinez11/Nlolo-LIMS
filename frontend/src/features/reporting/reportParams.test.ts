import { describe, expect, it } from 'vitest';
import type { ReportDefinition } from '@/api/reporting';
import { defaultFilters, formatCell, stateFromSearch, toParams, validateFilters } from './reportParams';

const definition = (dateFilterKind: ReportDefinition['dateFilterKind'], comparable = false): ReportDefinition => ({
  key: 'k',
  title: 'T',
  description: 'D',
  category: 'MANAGEMENT',
  dateFilterKind,
  groupByOptions: ['PRODUCT', 'AGENT'],
  comparable,
});

const TODAY = new Date(2026, 9, 11); // 11 October 2026, local time

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
