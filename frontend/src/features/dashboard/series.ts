import type { CountPoint, DaysPoint, MonthPoint, Period, Share } from '@/api/dashboard';
import { CHART_COLORS, monthLong, monthTick } from '@/components/charts/chartScale';
import type { DonutPart } from '@/components/charts/Donut';
import type { Category } from '@/components/charts/TrendChart';
import { countValue } from '@/lib/counts';
import { formatMoney, formatMoneyCompact } from '@/lib/money';
import { humanizeStatus } from '@/lib/status';
import { describeRange } from './range';

/**
 * Turning the server's series into what the charts draw (2026-10-23). Parsing a decimal string into a number here is
 * DRAWING: it sets a height. Every figure a reader sees is the server's own string through `formatMoney`.
 */

/** The axis for a run of months; with the year on each tick when the run crosses a new year. */
export function monthCategories(months: string[]): Category[] {
  const years = new Set(months.map((m) => m.slice(0, 4)));
  return months.map((m) => ({ key: m, tick: monthTick(m, years.size > 1), long: monthLong(m) }));
}

export function moneyPoints(points: MonthPoint[], rekey?: (month: string) => string) {
  return points.map((p) => ({
    key: rekey ? rekey(p.month) : p.month,
    value: p.amount ? Number(p.amount.amount) : null,
    display: formatMoney(p.amount),
  }));
}

export function countPoints(points: CountPoint[]) {
  return points.map((p) => ({ key: p.month, value: p.count, display: countValue(p.count) ?? '0' }));
}

export function dayPoints(points: DaysPoint[]) {
  return points.map((p) => ({
    key: p.month,
    value: p.averageDays === null ? null : Number(p.averageDays),
    display: p.averageDays === null ? 'Nothing decided' : `${p.averageDays} days`,
  }));
}

/**
 * Shares as donut slices, in the server's order, colours taken in order. Money shares show compact amounts in the list;
 * count shares show counts. A share past the fifth folds into the last slice's colour family -- the server keeps the
 * lists short, and five is the palette.
 */
export function donutParts(shares: Share[], word: (key: string) => string = humanizeStatus): DonutPart[] {
  return shares.slice(0, CHART_COLORS.length).map((s, i) => ({
    key: s.key,
    label: word(s.key),
    value: s.amount ? Number(s.amount.amount) : (s.count ?? 0),
    display: s.amount ? formatMoneyCompact(s.amount) : (countValue(s.count ?? null) ?? ''),
    percent: s.percent,
    color: CHART_COLORS[i]!,
  }));
}

/** "vs Jan to Oct 2025" -- what a change on a tile is against. */
export function versus(period: Period): string {
  return `vs ${describeRange(period.compareFrom, period.compareTo)}`;
}

/** The newest values of a series for a sparkline, gaps dropped. */
export function trendOf(points: { value: number | null }[]): number[] {
  return points.map((p) => p.value).filter((v): v is number => v !== null);
}
