import { describe, expect, it } from 'vitest';
import { formatMoney } from './money';

describe('formatMoney', () => {
  it('formats a whole-shilling amount with thousands separators', () => {
    expect(formatMoney('1500000', 'TZS')).toBe('TZS 1,500,000.00');
  });

  it('keeps exactly two decimal places', () => {
    expect(formatMoney('1234.5', 'TZS')).toBe('TZS 1,234.50');
  });

  it('renders zero without special-casing it', () => {
    // Cash value is TZS 0.00 platform-wide today; the portal shows it as-is.
    expect(formatMoney('0', 'TZS')).toBe('TZS 0.00');
  });

  it('handles a negative amount', () => {
    expect(formatMoney('-250.25', 'TZS')).toBe('TZS -250.25');
  });

  it('does not lose precision on a value beyond float safety', () => {
    // 9007199254740993 is 2^53+1 — unrepresentable as a JS number. This test is the reason
    // formatMoney must not go through parseFloat/Number anywhere.
    expect(formatMoney('9007199254740993', 'TZS')).toBe('TZS 9,007,199,254,740,993.00');
  });

  it('rejects a malformed amount loudly rather than rendering NaN', () => {
    expect(() => formatMoney('abc', 'TZS')).toThrow(/malformed money amount/i);
  });
});
