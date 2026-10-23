import { describe, expect, it } from 'vitest';
import { describeRange, keyFor, rangeFor, shiftMonth } from './range';

describe('dashboard ranges', () => {
  it('turns each preset into months ending this month', () => {
    expect(rangeFor('month', '2026-10')).toEqual({ from: '2026-10', to: '2026-10' });
    expect(rangeFor('quarter', '2026-10')).toEqual({ from: '2026-10', to: '2026-10' });
    expect(rangeFor('quarter', '2026-08')).toEqual({ from: '2026-07', to: '2026-08' });
    expect(rangeFor('ytd', '2026-10')).toEqual({ from: '2026-01', to: '2026-10' });
    expect(rangeFor('12m', '2026-10')).toEqual({ from: '2025-11', to: '2026-10' });
  });

  it('recognises a preset from its months, and calls anything else custom', () => {
    expect(keyFor({ from: '2026-01', to: '2026-10' }, '2026-10')).toBe('ytd');
    expect(keyFor({}, '2026-10')).toBe('ytd');
    expect(keyFor({ from: '2026-03', to: '2026-05' }, '2026-10')).toBe('custom');
  });

  it('moves across a year end and says a range plainly', () => {
    expect(shiftMonth('2026-01', -1)).toBe('2025-12');
    expect(shiftMonth('2025-09', 12)).toBe('2026-09');
    expect(describeRange('2026-07', '2026-09')).toBe('Jul to Sep 2026');
    expect(describeRange('2025-11', '2026-10')).toBe('Nov 2025 to Oct 2026');
    expect(describeRange('2026-10', '2026-10')).toBe('Oct 2026');
  });
});
