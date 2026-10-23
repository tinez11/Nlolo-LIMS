import type { ReactNode } from 'react';
import { cn } from '@/lib/cn';
import { BG, BORDER, type ChartColor } from './chartScale';

/**
 * The hover card a chart shows over the mark under the pointer (2026-10-23). Positioned by the chart in percent of its
 * own box, so it follows the drawing at any width; kept inside the box by flipping to the left past the middle.
 */
export function ChartTip({ x, y, title, rows }: {
  /** Percent of the chart's width and height. */
  x: number;
  y: number;
  title: string;
  rows: { label: string; value: string; color?: ChartColor | undefined; dashed?: boolean | undefined }[];
}) {
  return (
    <div
      role="status"
      className={cn(
        'pointer-events-none absolute z-10 min-w-36 rounded-lg bg-foreground px-3 py-2 text-xs text-background shadow-card',
        x > 55 ? '-translate-x-[calc(100%+12px)]' : 'translate-x-3',
      )}
      style={{ left: `${x}%`, top: `${Math.max(0, y)}%` }}
    >
      <div className="font-semibold">{title}</div>
      {rows.map((row) => (
        <div key={row.label} className="mt-1 flex items-center gap-2 tabular-nums">
          {row.color && <Swatch color={row.color} dashed={row.dashed} />}
          <span className="min-w-0 flex-1 opacity-80">{row.label}</span>
          <span className="font-semibold">{row.value}</span>
        </div>
      ))}
    </div>
  );
}

/** A legend's colour key: a square for an area or a bar, a short line for a trend, dashed for a comparison. */
export function Swatch({ color, line = false, dashed = false }: { color: ChartColor; line?: boolean | undefined; dashed?: boolean | undefined }) {
  if (dashed) {
    return <i aria-hidden className={cn('inline-block h-0 w-3.5 border-t-2 border-dashed', BORDER[color])} />;
  }
  return <i aria-hidden className={cn('inline-block shrink-0 rounded-sm', BG[color], line ? 'h-[3px] w-3.5' : 'size-2.5')} />;
}

/** A row of legend entries above a chart. Identity is never colour alone: every series is named here. */
export function Legend({ items }: { items: { label: string; color: ChartColor; line?: boolean | undefined; dashed?: boolean | undefined }[] }): ReactNode {
  return (
    <div className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-muted-foreground">
      {items.map((item) => (
        <span key={item.label} className="inline-flex items-center gap-1.5">
          <Swatch color={item.color} line={item.line} dashed={item.dashed} />
          {item.label}
        </span>
      ))}
    </div>
  );
}
