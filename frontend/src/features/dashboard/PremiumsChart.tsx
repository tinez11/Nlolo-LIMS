import { BarChart, type Series } from '@/components/charts/BarChart';
import { formatMoney, type Money } from '@/lib/money';

export interface MonthPoint {
  month: string;
  amount: Money | null;
}

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/**
 * Premiums collected by month, this year against last (2026-10-14; re-pointed at the shared BarChart 2026-10-16).
 *
 * Every bar is a value the reporting module returned (`monthlyPremiumCollections`); this adapts, it does not add.
 *
 * <p>Parsing the decimal strings to numbers here is DRAWING, not arithmetic on money: no total, average or
 * difference is produced, and nothing parsed is ever shown -- the tooltips and the described image carry
 * `formatMoney`'s string, built from the amount exactly as the server sent it.
 *
 * <p>The twelve months of the current year are always on the axis, so a thin year still reads as a year rather than
 * as a handful of bars. Last year's bars sit under this year's own months, which is what makes them comparable.
 */
export function PremiumsChart({
  thisYear,
  lastYear,
  currentMonth,
}: {
  thisYear: MonthPoint[];
  lastYear: MonthPoint[];
  currentMonth: string;
}) {
  if (thisYear.length === 0 && lastYear.length === 0) {
    return <p className="px-4 pb-4 text-xs text-muted-foreground">No premiums collected in either year yet.</p>;
  }

  const year = Number(currentMonth.slice(0, 4));
  const months = Array.from({ length: 12 }, (_, i) => `${year}-${String(i + 1).padStart(2, '0')}`);

  /** Last year's points are re-keyed onto this year's months, so the two series share one axis. */
  const toPoints = (points: MonthPoint[], shiftToCurrentYear: boolean) =>
    points
      .filter((p): p is MonthPoint & { amount: Money } => p.amount !== null)
      .map((p) => ({
        month: shiftToCurrentYear ? `${year}-${p.month.slice(5, 7)}` : p.month,
        value: Number(p.amount.amount),
        display: formatMoney(p.amount),
      }));

  const series: Series[] = [
    { label: String(year), tone: 'ink', partialMonth: currentMonth, points: toPoints(thisYear, false) },
    { label: String(year - 1), tone: 'muted', points: toPoints(lastYear, true) },
  ];

  const latest = thisYear.filter((p) => p.month !== currentMonth && p.amount !== null).at(-1);
  const description =
    `Premiums collected by month, ${year} against ${year - 1}.` +
    (latest?.amount ? ` ${MONTHS[Number(latest.month.slice(5, 7)) - 1]} ${year}: ${formatMoney(latest.amount)}.` : '') +
    (thisYear.some((p) => p.month === currentMonth && p.amount !== null)
      ? ` ${MONTHS[Number(currentMonth.slice(5, 7)) - 1]} is to date.`
      : '');

  return <BarChart series={series} months={months} description={description} />;
}
