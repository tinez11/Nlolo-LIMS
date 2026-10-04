/**
 * Date display.
 *
 * The API sends ISO calendar dates (`2026-03-29`) and instants (`...Z`). A calendar
 * date must NOT go through `new Date(...)` and then a local-timezone render: for a
 * user east of UTC that shifts a due date by a day, which on an invoice is the
 * difference between in-grace and overdue.
 */

const MONTHS = [
  'Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun',
  'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec',
] as const;

export const NO_DATE = '—';

/**
 * Format a plain ISO calendar date (`YYYY-MM-DD`) with no timezone conversion at
 * all -- the parts are read straight out of the string.
 */
export function formatDate(iso: string | null | undefined): string {
  if (!iso) return NO_DATE;
  const match = /^(\d{4})-(\d{2})-(\d{2})/.exec(iso);
  if (!match) return iso;
  const [, year, month, day] = match;
  const monthName = MONTHS[Number(month) - 1];
  if (!monthName) return iso;
  return `${monthName} ${Number(day)}, ${year}`;
}

/**
 * A policy term, as the years an insurance person would say out loud.
 *
 * Stored in months because a term is authored in months and the derived maturity is
 * `commencement + N months`; read as years because "20 years" is what appears on the
 * contract. Whole years render as years, anything else keeps the months so nothing is
 * rounded away -- a 30-month term must never display as "2 years".
 */
export function formatMonths(months: number | null | undefined): string {
  if (months == null) return NO_DATE;
  if (months % 12 === 0) {
    const years = months / 12;
    return `${years} year${years === 1 ? '' : 's'}`;
  }
  if (months < 12) return `${months} month${months === 1 ? '' : 's'}`;
  return `${Math.floor(months / 12)}y ${months % 12}m`;
}

/** Format an instant in the viewer's own timezone, which is correct for a timestamp. */
export function formatInstant(iso: string | null | undefined): string {
  if (!iso) return NO_DATE;
  const parsed = new Date(iso);
  if (Number.isNaN(parsed.getTime())) return iso;
  return new Intl.DateTimeFormat(undefined, {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(parsed);
}

/**
 * A calendar date `years` later, by string arithmetic -- as Java's `LocalDate.plusYears`, which the
 * server uses: 29 February in a year with none becomes 28 February.
 */
export function addYears(iso: string, years: number): string {
  const match = /^(\d{4})-(\d{2})-(\d{2})/.exec(iso);
  if (!match) return iso;
  const year = Number(match[1]) + years;
  const month = match[2]!;
  let day = match[3]!;
  const leap = (year % 4 === 0 && year % 100 !== 0) || year % 400 === 0;
  if (month === '02' && day === '29' && !leap) day = '28';
  return `${String(year).padStart(4, '0')}-${month}-${day}`;
}

/** Today as `YYYY-MM-DD` in local terms, for `asOf` query parameters. */
export function todayIso(): string {
  const now = new Date();
  const month = `${now.getMonth() + 1}`.padStart(2, '0');
  const day = `${now.getDate()}`.padStart(2, '0');
  return `${now.getFullYear()}-${month}-${day}`;
}
