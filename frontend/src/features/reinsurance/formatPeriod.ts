// Spelled out rather than taken from Intl: en-GB's short September is "Sept" in some ICU builds and "Sep" in others.
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/** An accounting month as people say it: "2026-09" -> "Sep 2026". Anything else is shown as it came. */
export function formatPeriod(period: string): string {
  const match = /^(\d{4})-(0[1-9]|1[0-2])$/.exec(period);
  if (!match) return period;
  return `${MONTHS[Number(match[2]) - 1]} ${match[1]}`;
}
