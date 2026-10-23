import { useId, useRef, useState, type KeyboardEvent, type MouseEvent } from 'react';
import { cn } from '@/lib/cn';
import { ChartTip } from './ChartTip';
import { useChartWidth } from './useChartWidth';
import { axisLabel, FILL, labelEvery, niceMax, STROKE, ticks, type ChartColor } from './chartScale';

export interface Category {
  key: string;
  /** The axis label. */
  tick: string;
  /** The tooltip's heading. */
  long: string;
}

export interface TrendSeries {
  label: string;
  color: ChartColor;
  /** A comparison drawn behind the subject, as a dashed line. */
  dashed?: boolean;
  /** A soft fill under the line. One series at most should carry it. */
  area?: boolean;
  /** Keyed by category. A null value is a GAP: the line breaks, it never dives to zero. */
  points: { key: string; value: number | null; display: string }[];
}

const L = 46;
const R = 12;
const T = 12;
const B = 26;

/**
 * A trend over time, one or more lines, drawn as inline SVG (2026-10-23).
 *
 * <b>It draws; it does not compute.</b> A value sets a point's height; the tooltip shows `display`, the server's own
 * string. <b>A missing value is a gap</b>, never a zero. <b>Hover or arrow keys</b> move a crosshair across the
 * categories and show every series' value there. One entrance, the line drawing itself in, and none under reduced
 * motion (index.css).
 */
export function TrendChart({
  categories,
  series,
  description,
  height = 240,
}: {
  categories: Category[];
  series: TrendSeries[];
  description: string;
  height?: number;
}) {
  const H = height;
  const gradient = useId();
  const box = useRef<SVGSVGElement>(null);
  // Laid out at the width it is shown, so 12px labels stay 12px in a half-width card.
  const [wrap, W] = useChartWidth<HTMLDivElement>();
  const [hover, setHover] = useState<number | null>(null);

  const values = series.flatMap((s) => s.points.map((p) => p.value ?? 0));
  const max = niceMax(Math.max(0, ...values) * 1.05);
  const n = categories.length;
  const x = (i: number) => L + ((W - L - R) * (n === 1 ? 0.5 : i / (n - 1)));
  const y = (v: number) => T + (H - T - B) * (1 - v / max);
  const every = labelEvery(n);

  const valueAt = (s: TrendSeries, key: string) => s.points.find((p) => p.key === key);

  /** Each run of known values is its own path, so a gap stays a gap. */
  const segments = (s: TrendSeries) => {
    const runs: [number, number][][] = [];
    let run: [number, number][] = [];
    categories.forEach((c, i) => {
      const v = valueAt(s, c.key)?.value;
      if (v === null || v === undefined) {
        if (run.length) runs.push(run);
        run = [];
      } else {
        run.push([x(i), y(v)]);
      }
    });
    if (run.length) runs.push(run);
    return runs;
  };

  const pick = (clientX: number) => {
    const rect = box.current?.getBoundingClientRect();
    if (!rect || n === 0) return;
    const px = ((clientX - rect.left) / rect.width) * W;
    const i = n === 1 ? 0 : Math.round(((px - L) / (W - L - R)) * (n - 1));
    setHover(Math.max(0, Math.min(n - 1, i)));
  };

  const onKey = (e: KeyboardEvent) => {
    if (e.key === 'ArrowRight') setHover((h) => Math.min(n - 1, (h ?? -1) + 1));
    else if (e.key === 'ArrowLeft') setHover((h) => Math.max(0, (h ?? n) - 1));
    else if (e.key === 'Escape') setHover(null);
    else return;
    e.preventDefault();
  };

  const hovered = hover === null ? null : categories[hover];

  return (
    <div className="relative" ref={wrap}>
      <svg
        ref={box}
        viewBox={`0 0 ${W} ${H}`}
        className="block h-auto w-full overflow-visible outline-none focus-visible:ring-2 focus-visible:ring-ring"
        role="img"
        aria-label={description}
        tabIndex={0}
        onMouseMove={(e: MouseEvent) => pick(e.clientX)}
        onMouseLeave={() => setHover(null)}
        onKeyDown={onKey}
        onBlur={() => setHover(null)}
      >
        <defs>
          {series.filter((s) => s.area).map((s) => (
            <linearGradient key={s.label} id={`${gradient}-${s.color}`} x1="0" x2="0" y1="0" y2="1">
              <stop offset="0%" style={{ stopColor: `var(--${s.color})`, stopOpacity: 0.26 }} />
              <stop offset="100%" style={{ stopColor: `var(--${s.color})`, stopOpacity: 0.02 }} />
            </linearGradient>
          ))}
        </defs>
        {ticks(max).map((t) => (
          <g key={t}>
            <line x1={L} x2={W - R} y1={y(t)} y2={y(t)} className="stroke-border" />
            <text x={L - 8} y={y(t) + 4} textAnchor="end" className="fill-muted-foreground text-xs tabular-nums">
              {axisLabel(t)}
            </text>
          </g>
        ))}
        {categories.map((c, i) =>
          i % every === 0 || i === n - 1 ? (
            <text key={c.key} x={x(i)} y={H - 6} textAnchor="middle" className="fill-muted-foreground text-xs">
              {c.tick}
            </text>
          ) : null,
        )}
        {series.map((s) =>
          segments(s).map((run, r) => {
            const d = run.map(([px, py], k) => `${k ? 'L' : 'M'}${px.toFixed(1)},${py.toFixed(1)}`).join('');
            const first = run[0];
            const last = run[run.length - 1];
            return (
              <g key={`${s.label}-${r}`}>
                {s.area && first && last && (
                  <path
                    d={`${d}L${last[0].toFixed(1)},${y(0)}L${first[0].toFixed(1)},${y(0)}Z`}
                    fill={`url(#${gradient}-${s.color})`}
                    className="chart-fade"
                  />
                )}
                <path
                  d={d}
                  fill="none"
                  className={cn(STROKE[s.color], s.dashed ? 'chart-fade' : 'chart-draw')}
                  strokeWidth={2.25}
                  strokeLinejoin="round"
                  strokeLinecap="round"
                  {...(s.dashed ? { strokeDasharray: '5 5' } : { pathLength: 1 })}
                />
              </g>
            );
          }),
        )}
        {series.map((s) => {
          // The newest known point is marked, so the reader's eye lands on "now".
          const lastIndex = categories.map((c) => valueAt(s, c.key)?.value).findLastIndex((v) => v !== null && v !== undefined);
          const v = lastIndex >= 0 ? valueAt(s, categories[lastIndex]!.key)?.value : null;
          if (s.dashed || v === null || v === undefined) return null;
          return <circle key={s.label} cx={x(lastIndex)} cy={y(v)} r={4} className={cn(FILL[s.color], 'stroke-surface chart-fade')} strokeWidth={2} />;
        })}
        {hover !== null && hovered && (
          <g>
            <line x1={x(hover)} x2={x(hover)} y1={T} y2={H - B} className="stroke-border-strong" />
            {series.map((s) => {
              const v = valueAt(s, hovered.key)?.value;
              return v === null || v === undefined ? null : (
                <circle key={s.label} cx={x(hover)} cy={y(v)} r={4.5} className={cn(FILL[s.color], 'stroke-surface')} strokeWidth={2} />
              );
            })}
          </g>
        )}
      </svg>
      {hover !== null && hovered && (
        <ChartTip
          x={(x(hover) / W) * 100}
          y={8}
          title={hovered.long}
          rows={series.map((s) => ({
            label: s.label,
            value: valueAt(s, hovered.key)?.display ?? 'No figure',
            color: s.color,
            dashed: s.dashed,
          }))}
        />
      )}
    </div>
  );
}
