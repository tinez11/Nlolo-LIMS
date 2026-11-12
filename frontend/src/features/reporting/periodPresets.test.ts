import { describe, expect, it } from 'vitest';
import { activePreset, periodPresets } from './periodPresets';
import type { ReportFilterState } from './reportParams';

const NOV_11 = new Date(2026, 10, 11);
const byId = (presets: ReturnType<typeof periodPresets>, id: string) => presets.find((p) => p.id === id)!;

const blank: ReportFilterState = {
  asOf: '', compareAsOf: '', from: '', to: '', cohortFrom: '', cohortTo: '', groupBy: '', clientType: '',
  productId: '', monthFrom: '', monthTo: '', accountCode: '', compareWith: '',
};

describe('periodPresets', () => {
  it('offers the quarter so far, never past today, and the last whole quarter', () => {
    const presets = periodPresets('PERIOD', NOV_11);
    expect(byId(presets, 'quarter')).toEqual({
      id: 'quarter', label: 'This quarter (Oct–Dec 2026)', set: { from: '2026-10-01', to: '2026-11-11' },
    });
    expect(byId(presets, 'lastQuarter')).toEqual({
      id: 'lastQuarter', label: 'Last quarter (Jul–Sep 2026)', set: { from: '2026-07-01', to: '2026-09-30' },
    });
    expect(byId(presets, 'year').set).toEqual({ from: '2026-01-01', to: '2026-11-11' });
    expect(byId(presets, 'lastMonth').set).toEqual({ from: '2026-10-01', to: '2026-10-31' });
  });

  it('gives a run of months for an accounting report', () => {
    expect(byId(periodPresets('MONTH_RANGE', NOV_11), 'lastQuarter').set).toEqual({ monthFrom: '2026-07', monthTo: '2026-09' });
    expect(byId(periodPresets('MONTH_RANGE', NOV_11), 'quarter').set).toEqual({ monthFrom: '2026-10', monthTo: '2026-11' });
    expect(periodPresets('MONTH', NOV_11).map((p) => p.id)).toEqual(['month', 'lastMonth']);
  });

  it('gives quarter ends for a report on one day', () => {
    expect(byId(periodPresets('AS_OF', NOV_11), 'endLastQuarter').set).toEqual({ asOf: '2026-09-30' });
    expect(byId(periodPresets('AS_OF', NOV_11), 'endLastYear').set).toEqual({ asOf: '2025-12-31' });
  });

  it('crosses into last year in January', () => {
    const jan = periodPresets('PERIOD', new Date(2027, 0, 5));
    expect(byId(jan, 'lastQuarter')).toMatchObject({ label: 'Last quarter (Oct–Dec 2026)', set: { from: '2026-10-01', to: '2026-12-31' } });
    expect(byId(jan, 'lastMonth').set).toEqual({ from: '2026-12-01', to: '2026-12-31' });
  });

  it('offers nothing for activation months', () => {
    expect(periodPresets('COHORT', NOV_11)).toEqual([]);
  });
});

describe('activePreset', () => {
  it('names the choice the dates match, and none for hand-picked dates', () => {
    const presets = periodPresets('PERIOD', NOV_11);
    expect(activePreset(presets, { ...blank, from: '2026-10-01', to: '2026-11-11' })).toBe('quarter');
    expect(activePreset(presets, { ...blank, from: '2026-10-05', to: '2026-11-11' })).toBeNull();
  });
});
