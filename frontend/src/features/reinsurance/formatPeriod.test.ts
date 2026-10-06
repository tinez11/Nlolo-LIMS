import { describe, expect, it } from 'vitest';
import { formatPeriod } from './formatPeriod';

describe('formatPeriod', () => {
  it('reads an accounting month as month and year', () => {
    expect(formatPeriod('2026-09')).toBe('Sep 2026');
    expect(formatPeriod('2027-01')).toBe('Jan 2027');
  });

  it('shows anything that is not a month as it came', () => {
    expect(formatPeriod('2026-13')).toBe('2026-13');
    expect(formatPeriod('')).toBe('');
  });
});
