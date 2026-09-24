/**
 * Money on this platform is ALWAYS a decimal string plus an ISO currency code --
 * never a JSON number. The backend validates `^-?\d+(\.\d{1,2})?$` and stores
 * decimals, so any round-trip through a JS double is a correctness bug, not a
 * style preference: '9007199254740993.99' becomes '...994.00' as a Number.
 *
 * Everything here therefore keeps the string as the source of truth. There is no
 * arithmetic in this module on purpose -- the backend computes every total.
 */

/** Shape of `openapi-common.yaml#/components/schemas/Money`. */
export interface Money {
  amount: string;
  currencyCode: string;
}

/** The backend's own amount regex, re-exported so form validation cannot drift from it. */
export const AMOUNT_PATTERN = /^-?\d+(\.\d{1,2})?$/;

/** Rendered in place of a null NullableMoney or an absent figure. */
export const NO_VALUE = '—';

/**
 * True when `value` is exactly what the backend would accept as a Money amount.
 *
 * Doubles as a type predicate. `Intl.NumberFormat.format` is typed to accept
 * `Intl.StringNumericLiteral` (`` `${number}` ``) rather than a plain string, and
 * anything matching `^-?\d+(\.\d{1,2})?$` genuinely is one -- so this narrows
 * honestly instead of needing a cast at the call site.
 */
export function isValidAmount(value: string): value is Intl.StringNumericLiteral {
  return AMOUNT_PATTERN.test(value);
}

// Intl.NumberFormat accepts a string and formats it exactly, with no float
// conversion (Intl.NumberFormat v3). Passing a Number here would silently lose
// precision above 2^53. Built once -- constructing a formatter per cell is
// measurably slow in a long table.
const GROUPED_2DP = new Intl.NumberFormat('en-US', {
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
  useGrouping: true,
});

/**
 * Compare two Money amounts. Negative when `a < b`, zero when equal, positive when `a > b`.
 *
 * This module says it holds no arithmetic on purpose, and that still stands: nothing here adds,
 * subtracts or totals. A comparison is different in kind — it is how a form can refuse an amount
 * the backend would refuse anyway, before the round trip.
 *
 * <b>Via BigInt, never Number.</b> The whole reason money travels as a string here is that a
 * double cannot hold every decimal the backend stores, and `Number('9007199254740993.99')` is
 * already wrong. A ceiling check that quietly loses the last digits would pass an amount the
 * server then rejects — reintroducing the exact confusion it exists to prevent, one round trip
 * later. Both sides are scaled to integer cents and compared exactly.
 *
 * Returns `NaN` for an amount that is not in the backend's own format, so callers can tell
 * "cannot compare" apart from "not greater". Nothing may treat that as "within the limit".
 */
export function compareAmounts(a: string, b: string): number {
  if (!isValidAmount(a) || !isValidAmount(b)) return NaN;
  const cents = (value: string): bigint => {
    const negative = value.startsWith('-');
    const [whole, fraction = ''] = (negative ? value.slice(1) : value).split('.');
    const scaled = BigInt(whole) * 100n + BigInt(fraction.padEnd(2, '0'));
    return negative ? -scaled : scaled;
  };
  const left = cents(a);
  const right = cents(b);
  return left === right ? 0 : left < right ? -1 : 1;
}

/**
 * `a - b`, as a Money amount string. Exact, via BigInt cents.
 *
 * <b>This is the one exception to "no arithmetic here", and it is narrower than it looks.</b> The
 * rule exists so that totals — a scheme's cover, a ledger balance, a premium — are computed once,
 * by the backend, and never recomputed into a second slightly different answer on screen. This
 * computes no total. It states the DIFFERENCE between two figures a person is already looking at,
 * so a warning can say "TZS 300,000.00 less than this claim is covered for" instead of making
 * somebody subtract two seven-digit numbers in their head while approving a death claim.
 *
 * The result is for display only. It is never sent to the server, never persisted, and never
 * feeds another calculation. Anything that needs a real figure asks the backend for it.
 *
 * Returns `null` when either side is not a valid amount, so a caller cannot render a number
 * derived from something half-typed.
 */
export function subtractAmounts(a: string, b: string): string | null {
  if (!isValidAmount(a) || !isValidAmount(b)) return null;
  const cents = (value: string): bigint => {
    const negative = value.startsWith('-');
    const [whole, fraction = ''] = (negative ? value.slice(1) : value).split('.');
    const scaled = BigInt(whole) * 100n + BigInt(fraction.padEnd(2, '0'));
    return negative ? -scaled : scaled;
  };
  const difference = cents(a) - cents(b);
  const sign = difference < 0n ? '-' : '';
  const magnitude = difference < 0n ? -difference : difference;
  return `${sign}${magnitude / 100n}.${String(magnitude % 100n).padStart(2, '0')}`;
}

/**
 * Format a Money for display, e.g. `TZS 1,000,000.00`.
 *
 * The ISO code is used rather than a locale currency symbol: TZS has no widely
 * recognised glyph, and staff reading reinsurance or GL screens need the code to
 * be unambiguous.
 *
 * A malformed amount is passed through verbatim so a backend defect is visible on
 * screen instead of crashing the table or being coerced into a plausible number.
 */
export function formatMoney(money: Money | null | undefined): string {
  if (!money) return NO_VALUE;
  const { amount, currencyCode } = money;
  if (!isValidAmount(amount)) return `${currencyCode} ${amount}`;
  return `${currencyCode} ${GROUPED_2DP.format(amount)}`;
}
