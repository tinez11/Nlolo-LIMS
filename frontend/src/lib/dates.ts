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

/** Today as `YYYY-MM-DD` in local terms, for `asOf` query parameters. */
export function todayIso(): string {
  const now = new Date();
  const month = `${now.getMonth() + 1}`.padStart(2, '0');
  const day = `${now.getDate()}`.padStart(2, '0');
  return `${now.getFullYear()}-${month}-${day}`;
}
