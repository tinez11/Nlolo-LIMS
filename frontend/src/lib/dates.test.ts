import { describe, expect, it } from 'vitest';
import { formatDate, formatInstant, NO_DATE } from './dates';

describe('formatDate', () => {
  it('formats an ISO calendar date', () => {
    expect(formatDate('2026-03-29')).toBe('Mar 29, 2026');
  });

  it('drops a leading zero from the day', () => {
    expect(formatDate('2026-01-05')).toBe('Jan 5, 2026');
  });

  /**
   * The reason this does not use `new Date(iso).toLocaleDateString()`. That parses a
   * bare date as UTC midnight, so anywhere west of UTC it renders the PREVIOUS day.
   * On an invoice due date that is the difference between in-grace and overdue.
   */
  it('does not shift the date by timezone', () => {
    // Jan 1 must stay Jan 1 regardless of where the test runs.
    expect(formatDate('2026-01-01')).toBe('Jan 1, 2026');
    expect(formatDate('2026-12-31')).toBe('Dec 31, 2026');
  });

  it('ignores a time component on a date field', () => {
    expect(formatDate('2026-03-29T23:59:59Z')).toBe('Mar 29, 2026');
  });

  it.each([null, undefined, ''])('renders %s as the no-date marker', (value) => {
    expect(formatDate(value)).toBe(NO_DATE);
  });

  it('passes an unparseable value through rather than inventing a date', () => {
    expect(formatDate('not-a-date')).toBe('not-a-date');
  });

  it('does not accept a nonsense month as a real month', () => {
    expect(formatDate('2026-13-01')).toBe('2026-13-01');
  });
});

describe('formatInstant', () => {
  it('renders a real instant', () => {
    expect(formatInstant('2026-03-29T10:30:00Z')).not.toBe(NO_DATE);
    expect(formatInstant('2026-03-29T10:30:00Z')).toContain('2026');
  });

  it.each([null, undefined, ''])('renders %s as the no-date marker', (value) => {
    expect(formatInstant(value)).toBe(NO_DATE);
  });

  it('passes an unparseable value through', () => {
    expect(formatInstant('nope')).toBe('nope');
  });
});
