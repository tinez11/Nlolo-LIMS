import { formatMoney, type Money } from '@/lib/money';

export interface MonthPoint {
  month: string;
  amount: Money | null;
}

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/**
 * Premiums collected by month, this year against last (2026-10-14).
 *
 * Every bar is a value the reporting module returned (`monthlyPremiumCollections`); this draws, it does not add.
 * Achromatic by rule: this year in ink, last year in the strong rule grey -- a series is not a state, so it takes no
 * status hue. The month in progress is an ink OUTLINE: a half-month drawn solid beside complete months would read as
 * a collapse that has not happened.
 *
 * Heights are proportions of the largest value for display only. Parsing the decimal strings to numbers here is
 * drawing, not arithmetic on money: no total, average or difference is produced and nothing parsed is ever shown --
 * the labels and titles show the strings as the server sent them.
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

  const byMonth = (points: MonthPoint[]) => new Map(points.map((pt) => [Number(pt.month.slice(5, 7)), pt]));
  const cur = byMonth(thisYear);
  const last = byMonth(lastYear);
  const max = Math.max(1, ...[...thisYear, ...lastYear].map((pt) => Number(pt.amount?.amount ?? 0)));
  const year = Number(currentMonth.slice(0, 4));

  const W = 640,
    H = 220,
    L = 8,
    R = 8,
    T = 10,
    B = 26;
  const slot = (W - L - R) / 12;
  const bw = Math.min(14, (slot - 6) / 2);
  const h = (pt: MonthPoint | undefined) => (pt?.amount ? (Number(pt.amount.amount) / max) * (H - T - B) : 0);

  const label = (pt: MonthPoint | undefined) => (pt?.amount ? formatMoney(pt.amount) : 'nothing collected');
  const latest = thisYear.filter((pt) => pt.month !== currentMonth).at(-1);
  const description =
    `Premiums collected by month, ${year} against ${year - 1}.` +
    (latest ? ` ${MONTHS[Number(latest.month.slice(5, 7)) - 1]} ${year}: ${label(latest)}.` : '') +
    (cur.get(Number(currentMonth.slice(5, 7))) ? ` ${MONTHS[Number(currentMonth.slice(5, 7)) - 1]} is to date.` : '');

  return (
    <div className="px-4 pb-2">
      <svg viewBox={`0 0 ${W} ${H}`} className="block h-auto w-full" role="img" aria-label={description}>
        <line x1={L} x2={W - R} y1={H - B} y2={H - B} className="stroke-border" />
        {MONTHS.map((name, i) => {
          const m = i + 1;
          const cx = L + slot * i + slot / 2;
          const prev = last.get(m);
          const now = cur.get(m);
          const partial = `${year}-${String(m).padStart(2, '0')}` === currentMonth;
          return (
            <g key={name}>
              {prev && (
                <rect
                  x={cx - bw - 1}
                  y={H - B - h(prev)}
                  width={bw}
                  height={h(prev)}
                  rx={2}
                  className="fill-border-strong"
                >
                  <title>{`${name} ${year - 1}: ${label(prev)}`}</title>
                </rect>
              )}
              {now && (
                <rect
                  x={cx + 1}
                  y={H - B - h(now)}
                  width={bw}
                  height={h(now)}
                  rx={2}
                  className={partial ? 'fill-none stroke-foreground stroke-[1.5]' : 'fill-foreground'}
                  {...(partial ? { 'data-partial': 'true' } : {})}
                >
                  <title>{`${name} ${year}${partial ? ' to date' : ''}: ${label(now)}`}</title>
                </rect>
              )}
              <text x={cx} y={H - 8} textAnchor="middle" className="fill-muted-foreground text-xs">
                {name}
              </text>
            </g>
          );
        })}
      </svg>
      <div className="flex flex-wrap gap-4 pt-1 text-xs text-muted-foreground">
        <span className="inline-flex items-center gap-1.5">
          <i className="inline-block size-2.5 rounded-sm bg-foreground" />
          {year}
        </span>
        <span className="inline-flex items-center gap-1.5">
          <i className="inline-block size-2.5 rounded-sm bg-border-strong" />
          {year - 1}
        </span>
        <span className="inline-flex items-center gap-1.5">
          <i className="inline-block size-2.5 rounded-sm border-[1.5px] border-foreground" />
          this month, to date
        </span>
      </div>
    </div>
  );
}
