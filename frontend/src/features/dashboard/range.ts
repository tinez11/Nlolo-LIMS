import type { MonthRange } from '@/api/dashboard';
import { monthTick } from '@/components/charts/chartScale';

/**
 * The dashboard's range buttons, as months (2026-10-23). Picking months is the browser's only part: the server decides
 * everything the months then cover -- what they compare with, every total and every average.
 */
export type RangeKey = 'month' | 'quarter' | 'ytd' | '12m' | 'custom';

export const RANGE_LABEL: Record<RangeKey, string> = {
  month: 'This month',
  quarter: 'Quarter',
  ytd: 'Year to date',
  '12m': 'Last 12 months',
  custom: 'Custom',
};

/** "YYYY-MM" of a civil date in Dar es Salaam. */
export function monthOf(date: Date): string {
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone: 'Africa/Dar_es_Salaam', year: 'numeric', month: '2-digit' })
    .formatToParts(date);
  const y = parts.find((p) => p.type === 'year')?.value ?? '1970';
  const m = parts.find((p) => p.type === 'month')?.value ?? '01';
  return `${y}-${m}`;
}

/** {@code month} moved by {@code by} months. */
export function shiftMonth(month: string, by: number): string {
  const index = Number(month.slice(0, 4)) * 12 + Number(month.slice(5, 7)) - 1 + by;
  return `${Math.floor(index / 12)}-${String((index % 12) + 1).padStart(2, '0')}`;
}

/** The months a preset covers, ending with the current month. */
export function rangeFor(key: Exclude<RangeKey, 'custom'>, current: string): Required<MonthRange> {
  switch (key) {
    case 'month':
      return { from: current, to: current };
    case 'quarter': {
      const m = Number(current.slice(5, 7));
      return { from: `${current.slice(0, 4)}-${String(m - ((m - 1) % 3)).padStart(2, '0')}`, to: current };
    }
    case 'ytd':
      return { from: `${current.slice(0, 4)}-01`, to: current };
    case '12m':
      return { from: shiftMonth(current, -11), to: current };
  }
}

/** Which preset a pair of months is, or custom. */
export function keyFor(range: MonthRange, current: string): RangeKey {
  if (!range.from && !range.to) return 'ytd';
  for (const key of ['month', 'quarter', 'ytd', '12m'] as const) {
    const r = rangeFor(key, current);
    if (r.from === range.from && r.to === (range.to ?? current)) return key;
  }
  return 'custom';
}

/** How the range reads in a sentence: "Jan to Oct 2026", "October 2026". */
export function describeRange(from: string, to: string): string {
  const name = (m: string) => monthTick(m, false);
  if (from === to) return `${name(from)} ${from.slice(0, 4)}`;
  return from.slice(0, 4) === to.slice(0, 4)
    ? `${name(from)} to ${name(to)} ${to.slice(0, 4)}`
    : `${name(from)} ${from.slice(0, 4)} to ${name(to)} ${to.slice(0, 4)}`;
}
