import { describe, expect, it } from 'vitest';
import { resolveJump } from './jump';

describe('resolveJump', () => {
  it('opens an exact policy number, whatever the case and padding', () => {
    expect(resolveJump('  pol-7k2q9  ')).toEqual({ to: 'policies/POL-7K2Q9', label: 'Open policy POL-7K2Q9' });
  });

  it('opens a receipt by the number on the customer’s SMS (receipts step 1)', () => {
    expect(resolveJump(' rc-2026-000014 ')).toEqual({ to: 'receipt-numbers/RC-2026-000014', label: 'Open receipt RC-2026-000014' });
  });

  it('declines anything that is not an exact reference', () => {
    // No search endpoint exists, so the palette must not pretend a name is findable.
    expect(resolveJump('Juma')).toBeNull();
    expect(resolveJump('POL-')).toBeNull();
    expect(resolveJump('POL-12 34')).toBeNull();
    expect(resolveJump('RC-2026-14')).toBeNull();
  });
});
