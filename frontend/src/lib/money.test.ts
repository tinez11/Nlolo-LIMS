import { describe, expect, it } from 'vitest';
import {
  AMOUNT_PATTERN,
  compareAmounts,
  formatMoney,
  isValidAmount,
  NO_VALUE,
  subtractAmounts,
} from './money';

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

describe('compareAmounts', () => {
  it('orders two amounts', () => {
    expect(compareAmounts('1500000.00', '800000.00')).toBeGreaterThan(0);
    expect(compareAmounts('800000.00', '1500000.00')).toBeLessThan(0);
  });

  it('treats differently written equal amounts as equal', () => {
    // The approval ceiling is INCLUSIVE, and a death claim normally pays the whole
    // of the cover -- so '800000' typed against a cover of '800000.00' has to be
    // allowed through, or the commonest correct settlement on the platform is
    // refused by the form.
    expect(compareAmounts('800000', '800000.00')).toBe(0);
    expect(compareAmounts('800000.0', '800000.00')).toBe(0);
  });

  /*
    THE REASON THIS USES BigInt.

    Number('9007199254740993.99') is 9007199254740994 -- above 2^53 a double cannot
    hold the value, and the two amounts below collapse onto the same one. A ceiling
    check built on Number would call an over-limit amount equal, pass it, and let the
    server refuse it: exactly the round trip the check exists to prevent.
  */
  it('separates amounts a double would collapse together', () => {
    expect(Number('9007199254740993.99')).toBe(Number('9007199254740993.98'));
    expect(compareAmounts('9007199254740993.99', '9007199254740993.98')).toBeGreaterThan(0);
  });

  it('is NaN when either side is not a valid amount, so nothing reads as within a limit', () => {
    expect(compareAmounts('1500', 'lots')).toBeNaN();
    // The guard that matters at a call site: a half-typed '1.' must not satisfy
    // `<= 0` and be treated as under the ceiling.
    expect(compareAmounts('1.', '800000.00') <= 0).toBe(false);
  });
});

describe('subtractAmounts', () => {
  it('states a shortfall exactly', () => {
    expect(subtractAmounts('800000.00', '500000.00')).toBe('300000.00');
  });

  it('keeps cents', () => {
    expect(subtractAmounts('1000.00', '0.01')).toBe('999.99');
  });

  it('borrows across the decimal point', () => {
    expect(subtractAmounts('1.00', '0.99')).toBe('0.01');
  });

  it('is exact past the double range', () => {
    expect(subtractAmounts('9007199254740993.99', '9007199254740993.98')).toBe('0.01');
  });

  it('signs a negative difference rather than dropping it', () => {
    expect(subtractAmounts('500000.00', '800000.00')).toBe('-300000.00');
  });

  it('returns null rather than a number derived from something half-typed', () => {
    expect(subtractAmounts('800000.00', '1.')).toBeNull();
    expect(subtractAmounts('', '1.00')).toBeNull();
  });
});
