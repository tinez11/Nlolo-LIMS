export interface Bucket {
  label: string;
  /** Height only. Never shown, never parsed back. */
  value: number;
  /** The string shown beside the bar, exactly as the server sent it. */
  display: string;
  count: number;
}

const ROW = 22;
const BAR = 12;
const LABEL_WIDTH = 110;
const AMOUNT_WIDTH = 130;
const W = 560;

/**
 * A horizontal bar per named bucket -- a CATEGORICAL chart, not a timeline (2026-10-16).
 *
 * <p>Its own component rather than the monthly {@link import('./BarChart').BarChart}, because five named buckets
 * forced through a monthly API would be a lie about what the data is.
 *
 * <p><b>No colour.</b> An age is not a status, and a red "over 90 days" bar would be the Stamp Rule broken in the
 * one place it is most tempting (DESIGN.md).
 *
 * <p><b>An empty bucket keeps its row.</b> "Nothing is this old" is the answer a reader came for, not missing data.
 *
 * <p>It draws; it does not compute. `value` scales the bar against the largest bucket and nothing else; `display`
 * is the figure, as text, beside it.
 */
export function BucketBars({ buckets, description }: { buckets: Bucket[]; description: string }) {
  const max = Math.max(1, ...buckets.map((b) => b.value));
  const track = W - LABEL_WIDTH - AMOUNT_WIDTH;
  const height = Math.max(ROW, buckets.length * ROW);

  return (
    <svg
      viewBox={`0 0 ${W} ${height}`}
      className="block h-auto w-full"
      role="img"
      aria-label={description}
    >
      {buckets.map((bucket, i) => {
        const y = i * ROW;
        const width = (bucket.value / max) * track;
        return (
          <g key={bucket.label}>
            <text x={0} y={y + BAR + 1} className="fill-foreground text-xs">
              {bucket.label}
            </text>
            <rect
              data-bar
              x={LABEL_WIDTH}
              y={y + 3}
              width={width}
              height={BAR}
              rx={2}
              className="fill-foreground"
            >
              <title>{`${bucket.label}: ${bucket.display}`}</title>
            </rect>
            <text x={W} y={y + BAR + 1} textAnchor="end" className="fill-foreground text-xs">
              {bucket.display}
            </text>
          </g>
        );
      })}
    </svg>
  );
}
