import { useRef, useState, type KeyboardEvent } from 'react';
import { cn } from '@/lib/cn';
import { ChartTip } from './ChartTip';
import { useChartWidth } from './useChartWidth';
import { axisLabel, FILL, labelEvery, niceMax, showsLabel, STROKE, ticks, type ChartColor } from './chartScale';
import type { Category } from './TrendChart';

export interface ColumnSeries {
  label: string;
  color: ChartColor;
  /** Keyed by category. A missing point draws nothing: no bar, not a zero-height one. */
  points: { key: string; value: number; display: string }[];
}

const L = 46;
const R = 8;
const T = 10;
const B = 26;
const GAP = 2;

/**
 * Columns per category -- side by side to compare, or stacked to show what a whole is made of (2026-10-23).
 *
 * <b>It draws; it does not compute.</b> A stack is drawn by placing each segment on the one below it, which is
 * geometry; no total is ever shown that the server did not send. <b>The category in progress</b> is drawn as an
 * outline, so a part month does not read as a collapse. Hover or arrow keys show a category's figures.
 */
export function ColumnChart({
  categories,
  series,
  description,
  stacked = false,
  partialKey,
  height = 240,
}: {
  categories: Category[];
  series: ColumnSeries[];
  description: string;
  stacked?: boolean;
  partialKey?: string | undefined;
  height?: number;
}) {
  const H = height;
  const box = useRef<SVGSVGElement>(null);
  // Laid out at the width it is shown, so 12px labels stay 12px in a half-width card.
  const [wrap, W] = useChartWidth<HTMLDivElement>();
  const [hover, setHover] = useState<number | null>(null);
  const n = categories.length;
  const valueOf = (s: ColumnSeries, key: string) => s.points.find((p) => p.key === key);

  const heights = categories.map((c) =>
    stacked
      ? series.reduce((top, s) => top + (valueOf(s, c.key)?.value ?? 0), 0)
      : Math.max(0, ...series.map((s) => valueOf(s, c.key)?.value ?? 0)),
  );
  const max = niceMax(Math.max(0, ...heights) * 1.05);
  const y = (v: number) => T + (H - T - B) * (1 - v / max);
  const band = (W - L - R) / Math.max(1, n);
  const groupWidth = Math.min(band * 0.68, stacked ? 38 : 52);
  const barWidth = stacked ? groupWidth : Math.max(2, (groupWidth - GAP * (series.length - 1)) / Math.max(1, series.length));
  const every = labelEvery(n);

  const onKey = (e: KeyboardEvent) => {
    if (e.key === 'ArrowRight') setHover((h) => Math.min(n - 1, (h ?? -1) + 1));
    else if (e.key === 'ArrowLeft') setHover((h) => Math.max(0, (h ?? n) - 1));
    else if (e.key === 'Escape') setHover(null);
    else return;
    e.preventDefault();
  };

  return (
    <div className="relative" ref={wrap}>
      <svg
        ref={box}
        viewBox={`0 0 ${W} ${H}`}
        className="block h-auto w-full overflow-visible outline-none focus-visible:ring-2 focus-visible:ring-ring"
        role="img"
        aria-label={description}
        tabIndex={0}
        onKeyDown={onKey}
        onMouseLeave={() => setHover(null)}
        onBlur={() => setHover(null)}
      >
        {ticks(max).map((t) => (
          <g key={t}>
            <line x1={L} x2={W - R} y1={y(t)} y2={y(t)} className="stroke-border" />
            <text x={L - 8} y={y(t) + 4} textAnchor="end" className="fill-muted-foreground text-xs tabular-nums">
              {axisLabel(t)}
            </text>
          </g>
        ))}
        {categories.map((c, i) => {
          const centre = L + band * i + band / 2;
          const partial = c.key === partialKey;
          let base = 0;
          const shown = series.filter((s) => (valueOf(s, c.key)?.value ?? 0) > 0);
          return (
            <g key={c.key} onMouseEnter={() => setHover(i)}>
              <rect x={L + band * i} y={T} width={band} height={H - T - B} className="fill-transparent" />
              {hover === i && <rect x={L + band * i + 2} y={T} width={band - 4} height={H - T - B} rx={6} className="fill-hover" />}
              {series.map((s, j) => {
                const v = valueOf(s, c.key)?.value ?? 0;
                if (v <= 0) return null;
                const left = stacked ? centre - groupWidth / 2 : centre - groupWidth / 2 + j * (barWidth + GAP);
                const bottom = stacked ? y(base) - (base > 0 ? GAP : 0) : y(0);
                const top = stacked ? y(base + v) : y(v);
                base += v;
                const h = Math.max(1, bottom - top);
                const isTop = !stacked || s === shown[shown.length - 1];
                const r = isTop ? Math.min(4, barWidth / 2, h) : 0;
                const d = `M${left},${bottom}V${top + r}a${r},${r} 0 0 1 ${r},${-r}h${barWidth - 2 * r}a${r},${r} 0 0 1 ${r},${r}V${bottom}Z`;
                return (
                  <path
                    key={s.label}
                    d={d}
                    // The month in progress: the same colour, lighter, with its edge drawn -- to date, not complete.
                    className={cn(FILL[s.color], partial && STROKE[s.color], 'chart-grow')}
                    fillOpacity={partial ? 0.35 : undefined}
                    strokeWidth={partial ? 1.5 : 0}
                    style={{ animationDelay: `${i * 22}ms` }}
                    {...(partial ? { 'data-partial': 'true' } : {})}
                  />
                );
              })}
              {(showsLabel(i, n, every)) && (
                <text x={centre} y={H - 6} textAnchor="middle" className="fill-muted-foreground text-xs">
                  {c.tick}
                </text>
              )}
            </g>
          );
        })}
      </svg>
      {hover !== null && categories[hover] && (
        <ChartTip
          x={((L + band * hover + band / 2) / W) * 100}
          y={8}
          title={`${categories[hover]!.long}${categories[hover]!.key === partialKey ? ', to date' : ''}`}
          rows={series.map((s) => ({
            label: s.label,
            value: valueOf(s, categories[hover]!.key)?.display ?? 'None',
            color: s.color,
          }))}
        />
      )}
    </div>
  );
}
