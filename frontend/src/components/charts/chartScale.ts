/**
 * The little geometry every chart shares (2026-10-23). Scales are DRAWING: a value sets a height and a tick, never a
 * figure anyone reads as data -- the tooltips and legends carry the server's own strings.
 */

/** The chart series colours, in the order a chart takes them. Never cycled: a sixth series folds into "Other". */
export const CHART_COLORS = ['chart-1', 'chart-2', 'chart-3', 'chart-4', 'chart-5'] as const;
export type ChartColor = (typeof CHART_COLORS)[number];

/** Tailwind needs whole class names in the source, so each role is spelled out per colour. */
export const FILL: Record<ChartColor, string> = {
  'chart-1': 'fill-chart-1',
  'chart-2': 'fill-chart-2',
  'chart-3': 'fill-chart-3',
  'chart-4': 'fill-chart-4',
  'chart-5': 'fill-chart-5',
};
export const STROKE: Record<ChartColor, string> = {
  'chart-1': 'stroke-chart-1',
  'chart-2': 'stroke-chart-2',
  'chart-3': 'stroke-chart-3',
  'chart-4': 'stroke-chart-4',
  'chart-5': 'stroke-chart-5',
};
export const BG: Record<ChartColor, string> = {
  'chart-1': 'bg-chart-1',
  'chart-2': 'bg-chart-2',
  'chart-3': 'bg-chart-3',
  'chart-4': 'bg-chart-4',
  'chart-5': 'bg-chart-5',
};
export const BORDER: Record<ChartColor, string> = {
  'chart-1': 'border-chart-1',
  'chart-2': 'border-chart-2',
  'chart-3': 'border-chart-3',
  'chart-4': 'border-chart-4',
  'chart-5': 'border-chart-5',
};
export const TEXT: Record<ChartColor, string> = {
  'chart-1': 'text-chart-1',
  'chart-2': 'text-chart-2',
  'chart-3': 'text-chart-3',
  'chart-4': 'text-chart-4',
  'chart-5': 'text-chart-5',
};

/** The top of the axis: the largest value rounded up to 1, 2, 2.5, 5 or 10 of its order, so ticks land on round values. */
export function niceMax(value: number): number {
  if (!(value > 0)) return 1;
  const power = 10 ** Math.floor(Math.log10(value));
  const m = value / power;
  return (m <= 1 ? 1 : m <= 2 ? 2 : m <= 2.5 ? 2.5 : m <= 5 ? 5 : 10) * power;
}

/** Four or five even steps, whichever lands every tick on a round value. */
export function ticks(max: number): number[] {
  const quarter = max / 4;
  const unit = 10 ** Math.floor(Math.log10(quarter || 1));
  const steps = Math.abs(quarter / unit - Math.round(quarter / unit)) < 1e-9 ? 4 : 5;
  return Array.from({ length: steps + 1 }, (_, i) => (max * i) / steps);
}

const COMPACT = new Intl.NumberFormat('en', { notation: 'compact', maximumFractionDigits: 1 });

/** A tick's label: 250M, 2.5k, 40. The axis, not data. */
export function axisLabel(value: number): string {
  return COMPACT.format(value);
}

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/** "2026-09" -> "Sep"; with the year when the axis spans more than one. */
export function monthTick(month: string, withYear: boolean): string {
  const name = MONTHS[Number(month.slice(5, 7)) - 1] ?? month;
  return withYear ? `${name} ${month.slice(2, 4)}` : name;
}

/** "2026-09" -> "September 2026", for tooltips and described images. */
export function monthLong(month: string): string {
  const names = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October',
    'November', 'December'];
  return `${names[Number(month.slice(5, 7)) - 1] ?? month} ${month.slice(0, 4)}`;
}

/** Which labels a crowded axis shows: every one up to 12, then about eight evenly, always the last. */
export function labelEvery(count: number): number {
  return count > 12 ? Math.ceil(count / 8) : 1;
}

/** Whether the label at {@code i} of {@code count} is drawn: every {@code every}th, the last, and none crowding the last. */
export function showsLabel(i: number, count: number, every: number): boolean {
  if (i === count - 1) return true;
  return i % every === 0 && count - 1 - i >= Math.ceil(every / 2);
}
