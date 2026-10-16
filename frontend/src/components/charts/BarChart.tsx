const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

export interface Series {
  /** Shown in the legend and in the described image. */
  label: string;
  /** 'ink' is the subject; 'muted' is what it is compared against. Never a hue. */
  tone: 'ink' | 'muted';
  /** One entry per month present. A month absent from every series is drawn as a gap. */
  points: { month: string; value: number; display: string }[];
  /** The month drawn as an outline because it is still running. */
  partialMonth?: string;
}

const W = 640;
const H = 220;
const L = 8;
const R = 8;
const T = 10;
const B = 26;
/** The space between two bars sharing one month. */
const GAP = 2;

/** "2026-09" -> "Sep 2026". */
function monthLabel(month: string): string {
  return `${MONTHS[Number(month.slice(5, 7)) - 1] ?? month} ${month.slice(0, 4)}`;
}

/**
 * A monthly bar chart, one or two series, drawn as inline SVG (2026-10-16).
 *
 * <b>It draws; it does not compute.</b> `value` sets a bar's height and nothing else. `display` is the string the
 * tooltip and the described image show, exactly as the server sent it -- the component never formats a number and
 * never parses `display` back into one. Heights are proportions of the largest value, which is drawing.
 *
 * <b>A month with no value is a GAP.</b> Not a zero, and not a line through it: the platform said nothing about that
 * month, so the chart says nothing.
 *
 * <b>Achromatic by rule.</b> Ink for the subject, the strong rule grey for what it is compared against. A series is
 * not a state, so it takes no status hue, and no bar is ever green or red -- a direction is not a verdict
 * (DESIGN.md, the Stamp Rule).
 *
 * <b>The month in progress is an outline.</b> A half-month drawn solid beside complete months reads as a collapse
 * that has not happened.
 *
 * <b>No charting library.</b> Twelve points do not earn 90KB and a second design system.
 */
export function BarChart({
  series,
  months,
  description,
}: {
  series: Series[];
  /** Every month the x-axis shows, oldest first, as "YYYY-MM". The caller decides the window. */
  months: string[];
  description: string;
}) {
  const slot = (W - L - R) / Math.max(1, months.length);
  const count = Math.max(1, series.length);
  const barWidth = Math.max(2, Math.min(14, (slot - 6 - GAP * (count - 1)) / count));
  const groupWidth = barWidth * count + GAP * (count - 1);

  const max = Math.max(1, ...series.flatMap((s) => s.points.map((p) => p.value)));
  const height = (value: number) => (value / max) * (H - T - B);

  return (
    <div className="px-4 pb-2">
      <svg viewBox={`0 0 ${W} ${H}`} className="block h-auto w-full" role="img" aria-label={description}>
        <line x1={L} x2={W - R} y1={H - B} y2={H - B} className="stroke-border" />
        {months.map((month, i) => {
          const centre = L + slot * i + slot / 2;
          const left = centre - groupWidth / 2;
          return (
            <g key={month}>
              {series.map((s, index) => {
                const point = s.points.find((p) => p.month === month);
                // The gap: a month this series says nothing about draws nothing at all.
                if (!point) return null;
                const partial = s.partialMonth === month;
                const barHeight = height(point.value);
                return (
                  <rect
                    key={s.label}
                    x={left + index * (barWidth + GAP)}
                    y={H - B - barHeight}
                    width={barWidth}
                    height={barHeight}
                    rx={2}
                    className={
                      partial
                        ? 'fill-none stroke-foreground stroke-[1.5]'
                        : s.tone === 'ink'
                          ? 'fill-foreground'
                          : 'fill-border-strong'
                    }
                    {...(partial ? { 'data-partial': 'true' } : {})}
                  >
                    <title>{`${s.label}, ${monthLabel(month)}${partial ? ' to date' : ''}: ${point.display}`}</title>
                  </rect>
                );
              })}
              <text x={centre} y={H - 8} textAnchor="middle" className="fill-muted-foreground text-xs">
                {MONTHS[Number(month.slice(5, 7)) - 1] ?? month}
              </text>
            </g>
          );
        })}
      </svg>
      <div className="flex flex-wrap gap-4 pt-1 text-xs text-muted-foreground">
        {series.map((s) => (
          <span key={s.label} className="inline-flex items-center gap-1.5">
            <i
              className={`inline-block size-2.5 rounded-sm ${s.tone === 'ink' ? 'bg-foreground' : 'bg-border-strong'}`}
            />
            {s.label}
          </span>
        ))}
        {series.some((s) => s.partialMonth) && (
          <span className="inline-flex items-center gap-1.5">
            <i className="inline-block size-2.5 rounded-sm border-[1.5px] border-foreground" />
            this month, to date
          </span>
        )}
      </div>
    </div>
  );
}
