import { describe, expect, it } from 'vitest';
import { canDecideAllocation, extractGate, parseAmount } from './expenseAllocation';

describe('expense allocation helpers', () => {
  it('reads an amount of at most two decimals, zero when blank', () => {
    expect(parseAmount('')).toBe(0);
    expect(parseAmount('  ')).toBe(0);
    expect(parseAmount('1,500.25')).toBe(1500.25);
    expect(parseAmount('30000000')).toBe(30000000);
    expect(parseAmount('1.234')).toBeNull();
    expect(parseAmount('-1')).toBeNull();
    expect(parseAmount('abc')).toBeNull();
  });

  it('lets the extract through only with a posted allocation', () => {
    expect(extractGate([])).toMatch(/^Step 5 first/);
    expect(extractGate([{ status: 'PREPARED' }])).toMatch(/^Step 5 first/);
    expect(extractGate([{ status: 'REPLACED' }, { status: 'POSTED' }])).toBeNull();
  });

  it('is decided by an approver who did not prepare it, while prepared', () => {
    const prepared = { status: 'PREPARED', preparedBy: 'u1' };
    expect(canDecideAllocation(prepared, 'u2', true)).toBe(true);
    expect(canDecideAllocation(prepared, 'u1', true)).toBe(false);
    expect(canDecideAllocation(prepared, 'u2', false)).toBe(false);
    expect(canDecideAllocation(prepared, null, true)).toBe(false);
    expect(canDecideAllocation({ status: 'POSTED', preparedBy: 'u1' }, 'u2', true)).toBe(false);
  });
});
