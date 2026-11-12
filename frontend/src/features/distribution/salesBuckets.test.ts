import { describe, expect, it } from 'vitest';
import { bucketDays, bucketWords } from './salesBuckets';

describe('sales buckets', () => {
  it('gives the days of a month, a quarter and a year, clipped to the span asked for', () => {
    expect(bucketDays('2026-02', '2025-01-01', '2026-11-12')).toEqual({ from: '2026-02-01', to: '2026-02-28' });
    expect(bucketDays('2026-Q4', '2025-01-01', '2026-11-12')).toEqual({ from: '2026-10-01', to: '2026-11-12' });
    expect(bucketDays('2025', '2025-03-15', '2026-11-12')).toEqual({ from: '2025-03-15', to: '2025-12-31' });
  });

  it('names a bucket as the report does', () => {
    expect(bucketWords('2026-10')).toBe('Oct 2026');
    expect(bucketWords('2026-Q4')).toBe('Oct–Dec 2026');
    expect(bucketWords('2026')).toBe('2026');
  });
});
