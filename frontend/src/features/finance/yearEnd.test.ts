import { describe, expect, it } from 'vitest';
import { canDecideClose, lastYear, resultLabel } from './yearEnd';

describe('year-end close helpers', () => {
  it('defaults to last year', () => {
    expect(lastYear(new Date(2026, 9, 7))).toBe(2025);
    expect(lastYear(new Date(2027, 0, 3))).toBe(2026);
  });

  it('names a profit or a loss', () => {
    expect(resultLabel(600)).toBe('Profit 600.00');
    expect(resultLabel(-1000)).toBe('Loss 1,000.00');
    expect(resultLabel(0)).toBe('Break-even');
  });

  it('is decided by an approver who did not prepare it, while prepared', () => {
    const prepared = { status: 'PREPARED', preparedBy: 'u1' };
    expect(canDecideClose(prepared, 'u2', true)).toBe(true);
    expect(canDecideClose(prepared, 'u1', true)).toBe(false);
    expect(canDecideClose(prepared, 'u2', false)).toBe(false);
    expect(canDecideClose({ status: 'POSTED', preparedBy: 'u1' }, 'u2', true)).toBe(false);
    expect(canDecideClose({ status: null, preparedBy: null }, 'u2', true)).toBe(false);
  });
});
