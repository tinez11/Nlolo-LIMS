import { useState } from 'react';
import { cn } from '@/lib/cn';
import { STROKE, type ChartColor } from './chartScale';
import { Swatch } from './ChartTip';

export interface DonutPart {
  key: string;
  label: string;
  /** Sets the slice's sweep -- drawing only. */
  value: number;
  /** The server's figure for the slice, shown in the list. */
  display: string;
  /** The server's share, "38.2"; null when it could not be said. */
  percent: string | null;
  color: ChartColor;
}

const S = 180;
const RADIUS = 66;
const STROKE_WIDTH = 22;
const C = 2 * Math.PI * RADIUS;

/**
 * Shares of a whole as a ring, with the whole in the middle and every slice named in a list beside it (2026-10-23).
 *
 * <b>Every number on it is the server's</b>: the slices' figures, their percents and the total in the centre. The ring
 * only turns values into angles. Hovering a slice or its row lifts it; the list is the accessible reading of the ring,
 * so no slice is identified by colour alone. Five slices at most -- a sixth folds into "Other" on the server's side.
 */
export function Donut({ parts, total, totalLabel, description }: {
  parts: DonutPart[];
  /** The whole, as the server sent it. */
  total: string;
  totalLabel: string;
  description: string;
}) {
  const [active, setActive] = useState<string | null>(null);
  const sum = parts.reduce((a, p) => a + Math.max(0, p.value), 0) || 1;
  const gap = parts.length > 1 ? 2.5 : 0;
  // Where each slice starts round the ring: the sweep of every slice before it.
  const starts = parts.map((_, i) => parts.slice(0, i).reduce((a, p) => a + (C * Math.max(0, p.value)) / sum, 0));

  return (
    <div className="grid grid-cols-1 items-center gap-5 sm:grid-cols-[minmax(8rem,10.5rem)_minmax(0,1fr)]">
      <svg viewBox={`0 0 ${S} ${S}`} className="mx-auto block h-auto w-full max-w-44" role="img" aria-label={description}>
        <circle cx={S / 2} cy={S / 2} r={RADIUS} fill="none" className="stroke-control" strokeWidth={STROKE_WIDTH} />
        {parts.map((p, i) => {
          const length = Math.max(0, (C * p.value) / sum - gap);
          return (
            <circle
              key={p.key}
              cx={S / 2}
              cy={S / 2}
              r={RADIUS}
              fill="none"
              className={cn(STROKE[p.color], 'chart-sweep transition-[stroke-width] duration-150')}
              strokeWidth={active === p.key ? STROKE_WIDTH + 6 : STROKE_WIDTH}
              strokeDasharray={`${length} ${C - length}`}
              strokeDashoffset={-(starts[i] ?? 0)}
              transform={`rotate(-90 ${S / 2} ${S / 2})`}
              style={{ animationDelay: `${i * 90}ms` }}
              onMouseEnter={() => setActive(p.key)}
              onMouseLeave={() => setActive(null)}
            />
          );
        })}
        <text x={S / 2} y={S / 2 + 4} textAnchor="middle" className="fill-foreground text-title font-extrabold tabular-nums">
          {total}
        </text>
        <text x={S / 2} y={S / 2 + 22} textAnchor="middle" className="fill-muted-foreground text-xs">
          {totalLabel}
        </text>
      </svg>
      <ul className="min-w-0 space-y-1.5">
        {parts.map((p) => (
          <li
            key={p.key}
            className={cn(
              'grid grid-cols-[0.75rem_minmax(0,1fr)_auto_2.75rem] items-center gap-2 rounded-md px-1.5 py-1 text-sm transition-colors',
              active === p.key && 'bg-hover',
            )}
            onMouseEnter={() => setActive(p.key)}
            onMouseLeave={() => setActive(null)}
          >
            <Swatch color={p.color} />
            <span className="truncate">{p.label}</span>
            <span className="font-semibold tabular-nums">{p.display}</span>
            <span className="text-right text-muted-foreground tabular-nums">{p.percent === null ? '' : `${p.percent}%`}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}
