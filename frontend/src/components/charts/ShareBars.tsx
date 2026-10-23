import { cn } from '@/lib/cn';
import { BG, type ChartColor } from './chartScale';

export interface ShareBar {
  key: string;
  label: string;
  /** Sets the bar's length against the longest -- drawing only. */
  value: number;
  /** The server's figure, shown at the end of the bar. */
  display: string;
  color: ChartColor;
  /** A second line under the label, such as "3 cases". */
  note?: string;
}

/**
 * Labelled horizontal bars, one per row (2026-10-23): stages of a queue, bands of what is owed, cover per person.
 * HTML rather than SVG so a long label wraps instead of shrinking with the drawing. Each bar grows in once.
 */
export function ShareBars({ bars, label }: { bars: ShareBar[]; label: string }) {
  const longest = Math.max(1, ...bars.map((b) => b.value));
  return (
    <ul aria-label={label} className="space-y-3">
      {bars.map((b, i) => (
        <li key={b.key} className="grid grid-cols-[minmax(6rem,32%)_minmax(0,1fr)_auto] items-center gap-3 text-sm">
          <span className="min-w-0">
            <span className="block">{b.label}</span>
            {b.note && <span className="block text-xs text-muted-foreground">{b.note}</span>}
          </span>
          <span className="h-5 overflow-hidden rounded-md bg-control" aria-hidden>
            <span
              className={cn('chart-grow-x block h-full rounded-md', BG[b.color])}
              style={{ width: `${Math.max(1.5, (b.value / longest) * 100)}%`, animationDelay: `${i * 50}ms` }}
            />
          </span>
          <span className="min-w-16 text-right font-semibold tabular-nums">{b.display}</span>
        </li>
      ))}
    </ul>
  );
}
