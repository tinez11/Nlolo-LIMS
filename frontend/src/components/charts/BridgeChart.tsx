import { cn } from '@/lib/cn';
import { useChartWidth } from './useChartWidth';
import { niceMax } from './chartScale';
import { bridgeSpans, labelLines, type BridgeBar } from './bridgeSpans';

export type { BridgeBar } from './bridgeSpans';

const T = 22;
const B = 40;
const X = 8;

const FILL: Record<BridgeBar['kind'], string> = {
  START: 'fill-chart-2',
  END: 'fill-chart-2',
  UP: 'fill-chart-1',
  DOWN: 'fill-chart-5',
};

const MONEY = new Intl.NumberFormat('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const WHOLE = new Intl.NumberFormat('en-US', { maximumFractionDigits: 0 });

/**
 * A bridge (waterfall) from a start figure to an end figure, one floating bar per step that raised or lowered it
 * (2026-11-09, the reports redesign). <b>It draws; it does not compute:</b> each step is placed on the one before,
 * which is geometry, and every label is the server's own figure. Rises and falls are named in words beside the colour.
 */
export function BridgeChart({ bars, description, height = 220, unit = 'MONEY' }: {
  bars: BridgeBar[];
  description: string;
  height?: number;
  /** COUNT for a movement of policies (2026-11-09): "12", not "12.00". */
  unit?: 'MONEY' | 'COUNT' | 'PERCENT';
}) {
  const NUMBER = unit === 'COUNT' ? WHOLE : MONEY;
  const [wrap, W] = useChartWidth<HTMLDivElement>();
  const H = height;
  const spans = bridgeSpans(bars);
  const high = niceMax(Math.max(0, ...spans.flatMap((s) => [s.from, s.to])) * 1.08 || 1);
  const low = Math.min(0, ...spans.flatMap((s) => [s.from, s.to]));
  const y = (v: number) => T + (H - T - B) * ((high - v) / (high - low || 1));
  const band = (W - 2 * X) / Math.max(1, bars.length);
  const width = Math.min(band * 0.62, 64);

  return (
    <div ref={wrap} className="w-full">
      <svg viewBox={`0 0 ${W} ${H}`} className="block h-auto w-full overflow-visible" role="img" aria-label={description}>
        <line x1={X} x2={W - X} y1={y(0)} y2={y(0)} className="stroke-border" />
        {bars.map((b, i) => {
          const s = spans[i]!;
          const left = X + band * i + (band - width) / 2;
          const top = y(Math.max(s.from, s.to));
          const h = Math.max(1.5, Math.abs(y(s.from) - y(s.to)));
          const words = b.kind === 'UP' ? 'up ' : b.kind === 'DOWN' ? 'down ' : '';
          return (
            <g key={`${b.label}-${i}`} data-kind={b.kind}>
              <rect x={left} y={top} width={width} height={h} rx={3} className={cn(FILL[b.kind], 'chart-grow')} />
              {i < bars.length - 1 && (
                <line
                  x1={left + width}
                  x2={X + band * (i + 1) + (band - width) / 2}
                  y1={y(s.to)}
                  y2={y(s.to)}
                  className="stroke-border"
                  strokeDasharray="3 3"
                />
              )}
              <text x={left + width / 2} y={top - 6} textAnchor="middle" className="fill-foreground text-xs font-semibold tabular-nums">
                {words}{NUMBER.format(Number(b.value))}
              </text>
              {/* Up to two lines under its bar, so neighbours never run into each other. */}
              <text x={left + width / 2} y={H - B + 16} textAnchor="middle" className="fill-muted-foreground text-xs">
                {labelLines(b.label, Math.max(8, Math.floor(band / 7))).map((line, k) => (
                  <tspan key={k} x={left + width / 2} dy={k === 0 ? 0 : 14}>{line}</tspan>
                ))}
              </text>
            </g>
          );
        })}
      </svg>
      <ul className="sr-only">
        {bars.map((b, i) => (
          <li key={`${b.label}-${i}`}>
            {b.label}: {b.kind === 'UP' ? 'up ' : b.kind === 'DOWN' ? 'down ' : ''}{b.value}
          </li>
        ))}
      </ul>
    </div>
  );
}
