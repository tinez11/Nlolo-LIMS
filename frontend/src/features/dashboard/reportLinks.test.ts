import { describe, expect, it } from 'vitest';
import { lastDayOf, reportLink } from './reportLinks';

describe('reportLink', () => {
  it('opens a report with the filter the figure was read with', () => {
    expect(reportLink('new-business', { from: '2026-01-01', to: '2026-03-31' }))
      .toBe('/staff/reports/new-business?from=2026-01-01&to=2026-03-31');
  });

  it('carries the dashboard branch, a code or every branch (2026-11-23)', () => {
    expect(reportLink('new-business', { from: '2026-01-01' }, 'ARU'))
      .toBe('/staff/reports/new-business?from=2026-01-01&branch=ARU');
    expect(reportLink('new-business', { from: '2026-01-01' }, 'all'))
      .toBe('/staff/reports/new-business?from=2026-01-01&branch=all');
  });
});

describe('lastDayOf', () => {
  it('knows a leap February', () => {
    expect(lastDayOf('2028-02')).toBe('2028-02-29');
  });
});
