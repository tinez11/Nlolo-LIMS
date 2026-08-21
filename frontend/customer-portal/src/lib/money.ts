/**
 * The ONLY place in the portal that parses a money string.
 *
 * The backend models money as `{ amount: string, currencyCode: string }` with a
 * `^-?\d+(\.\d{1,2})?$` pattern — deliberately a decimal string, not a float. Coercing it to
 * `number` anywhere would silently reintroduce the rounding the backend went out of its way to
 * avoid, so this function formats by string manipulation only: no parseFloat, no Number, no
 * Intl.NumberFormat on a numeric conversion.
 */
const MONEY_PATTERN = /^(-?)(\d+)(?:\.(\d{1,2}))?$/;

export function formatMoney(amount: string, currencyCode: string): string {
  const match = MONEY_PATTERN.exec(amount);
  if (!match) {
    throw new Error(`malformed money amount from the API: ${JSON.stringify(amount)}`);
  }
  const [, sign, whole, fraction = ''] = match;
  const grouped = whole.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  const cents = fraction.padEnd(2, '0');
  return `${currencyCode} ${sign}${grouped}.${cents}`;
}
