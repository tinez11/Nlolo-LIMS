import { describe, expect, it } from 'vitest';
import { AMOUNT_PATTERN, formatMoney, isValidAmount, NO_VALUE } from './money';

describe('formatMoney', () => {
  it('groups thousands and keeps exactly two decimals', () => {
    expect(formatMoney({ amount: '1000000.00', currencyCode: 'TZS' })).toBe('TZS 1,000,000.00');
  });

  it('pads a whole amount to two decimals', () => {
    expect(formatMoney({ amount: '50', currencyCode: 'TZS' })).toBe('TZS 50.00');
  });

  it('pads a single decimal place', () => {
    expect(formatMoney({ amount: '1234.5', currencyCode: 'TZS' })).toBe('TZS 1,234.50');
  });

  it('formats negatives', () => {
    expect(formatMoney({ amount: '-1234.5', currencyCode: 'TZS' })).toBe('TZS -1,234.50');
  });

  it('formats zero', () => {
    expect(formatMoney({ amount: '0', currencyCode: 'TZS' })).toBe('TZS 0.00');
  });

  // The whole reason Money is a string on this platform. Going through a JS double
  // turns this exact value into ...994.00 -- a silently wrong payout figure.
  it('is exact beyond Number.MAX_SAFE_INTEGER', () => {
    expect(formatMoney({ amount: '9007199254740993.99', currencyCode: 'TZS' })).toBe(
      'TZS 9,007,199,254,740,993.99',
    );
  });

  it('renders any currency code it is given', () => {
    expect(formatMoney({ amount: '10', currencyCode: 'USD' })).toBe('USD 10.00');
  });

  // NullableMoney exists in the specs (flatAmount, cededPremium), so null is a real
  // wire value, not a defect.
  it('renders null and undefined as the no-value marker', () => {
    expect(formatMoney(null)).toBe(NO_VALUE);
    expect(formatMoney(undefined)).toBe(NO_VALUE);
  });

  // A malformed amount is a backend defect. Surface it visibly rather than crashing
  // a whole table, and never quietly coerce it into a plausible-looking number.
  it('passes a malformed amount through visibly instead of throwing', () => {
    expect(formatMoney({ amount: 'not-a-number', currencyCode: 'TZS' })).toBe('TZS not-a-number');
  });

  it('does not coerce exponent notation into a formatted figure', () => {
    expect(formatMoney({ amount: '1e5', currencyCode: 'TZS' })).toBe('TZS 1e5');
  });
});

describe('isValidAmount', () => {
  // Mirrors the backend's own regex so the client rejects exactly what the server
  // would: ^-?\d+(\.\d{1,2})?$
  it.each(['0', '1', '-1', '1.5', '1.55', '-0.01', '9007199254740993.99'])(
    'accepts %s',
    (value) => {
      expect(isValidAmount(value)).toBe(true);
    },
  );

  it.each(['1.555', '1.', '.5', '', 'abc', '1e5', '+1', ' 1', '1 ', '1,000'])(
    'rejects %s',
    (value) => {
      expect(isValidAmount(value)).toBe(false);
    },
  );

  it('exposes the pattern for reuse in form validation', () => {
    expect(AMOUNT_PATTERN.source).toBe('^-?\\d+(\\.\\d{1,2})?$');
  });
});
