import { useId } from 'react';
import { STROKE, type ChartColor } from './chartScale';

const W = 112;
const H = 34;

/**
 * A figure's recent shape, beside the figure (2026-10-23). Decoration for the eye, not a reading: the figure and its
 * change are the facts, so the line is hidden from assistive technology and carries no tooltip.
 */
export function Sparkline({ values, color }: { values: number[]; color: ChartColor }) {
  const gradient = useId();
  if (values.length < 2) return null;
  const max = Math.max(...values);
  const min = Math.min(...values);
  const pts = values.map((v, i) => [
    (i * W) / (values.length - 1),
    4 + (H - 8) * (1 - (v - min) / (max - min || 1)),
  ] as const);
  const line = pts.map(([x, y], i) => `${i ? 'L' : 'M'}${x.toFixed(1)},${y.toFixed(1)}`).join('');
  const last = pts[pts.length - 1]!;
  return (
    <svg viewBox={`0 0 ${W} ${H}`} className="block h-8.5 w-28 shrink-0 overflow-visible" aria-hidden>
      <defs>
        <linearGradient id={gradient} x1="0" x2="0" y1="0" y2="1">
          <stop offset="0%" style={{ stopColor: `var(--${color})`, stopOpacity: 0.28 }} />
          <stop offset="100%" style={{ stopColor: `var(--${color})`, stopOpacity: 0 }} />
        </linearGradient>
      </defs>
      <path d={`${line}L${W},${H}L0,${H}Z`} fill={`url(#${gradient})`} className="chart-fade" />
      <path d={line} fill="none" className={`${STROKE[color]} chart-draw`} strokeWidth={2} strokeLinecap="round"
        strokeLinejoin="round" pathLength={1} />
      <circle cx={last[0]} cy={last[1]} r={3} style={{ fill: `var(--${color})` }} className="chart-fade" />
    </svg>
  );
}
