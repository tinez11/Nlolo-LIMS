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
