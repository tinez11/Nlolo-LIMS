import { ArrowDownRight, ArrowUpRight, type LucideIcon } from 'lucide-react';
import { Link } from 'react-router-dom';
import { BG, TEXT, type ChartColor } from '@/components/charts/chartScale';
import { Sparkline } from '@/components/charts/Sparkline';
import { cn } from '@/lib/cn';

export interface Stat {
  label: string;
  icon: LucideIcon;
  color: ChartColor;
  /** Already formatted. Null = could not be read. */
  value: string | null;
  /** The full figure for the screen reader, when `value` is shortened. */
  full?: string;
  /** The server's change against the comparison months, "12.4" or "-3.0"; null when there is none. */
  change?: string | null;
  /** Which way is good news. A queue growing is bad; premiums growing is good. */
  goodWhen?: 'up' | 'down';
  /** Words for what the change compares with, e.g. "vs Jan to Oct 2025". */
  versus?: string;
  /** The shape over the range: drawing only, from the same series the charts use. */
  trend?: number[];
  hint?: string;
  to?: string;
}

/**
 * A dashboard figure (2026-10-23): a tinted icon in the figure's own series colour, the figure, the change in green or
 * red by whether it is good news, and the recent shape beside it. The change's colour comes with an arrow and words,
 * so it is never colour alone.
 */
export function StatTile({ stat }: { stat: Stat }) {
  const { icon: Icon } = stat;
  const change = stat.change ?? null;
  const up = change !== null && !change.startsWith('-');
  const good = change !== null && (up ? (stat.goodWhen ?? 'up') === 'up' : (stat.goodWhen ?? 'up') === 'down');
  const body = (
    <>
      <span className="flex items-center gap-2.5 text-xs font-semibold text-muted-foreground">
        <span className={cn('relative grid size-7.5 shrink-0 place-items-center rounded-lg', TEXT[stat.color])}>
          <span className={cn('absolute size-7.5 rounded-lg opacity-15', BG[stat.color])} aria-hidden />
          <Icon className="relative size-4" aria-hidden />
        </span>
        {stat.label}
      </span>
      <span className="mt-2 block whitespace-nowrap text-figure">
        {stat.value === null ? (
          <span className="text-subtle-foreground" aria-hidden>—</span>
        ) : (
          <>
            <span aria-hidden={stat.full ? true : undefined}>{stat.value}</span>
            {stat.full && <span className="sr-only">{stat.full}</span>}
          </>
        )}
      </span>
      <span className="mt-1.5 flex items-end justify-between gap-2">
        <span className="min-w-0">
          {stat.value !== null && change !== null && (
            <span
              data-delta={good ? 'good' : 'bad'}
              className={cn(
                'inline-flex items-center gap-0.5 rounded-full py-0.5 pr-2 pl-1 text-xs font-bold',
                good ? 'bg-status-success-bg text-status-success-fg' : 'bg-status-danger-bg text-status-danger-fg',
              )}
            >
              {up ? <ArrowUpRight className="size-3.5" aria-hidden /> : <ArrowDownRight className="size-3.5" aria-hidden />}
              {change.replace(/^-/, '')}%
              <span className="sr-only">{up ? ' up' : ' down'}</span>
            </span>
          )}
          <span className="mt-1 block truncate text-xs text-subtle-foreground">
            {stat.value === null ? 'Not available' : change !== null ? stat.versus : stat.hint}
          </span>
        </span>
        {stat.trend && stat.value !== null && <Sparkline values={stat.trend} color={stat.color} />}
      </span>
    </>
  );
  const frame = 'relative @container block min-w-0 rounded-xl bg-surface px-4 py-3.5 shadow-card';
  if (!stat.to || stat.value === null) return <div className={frame}>{body}</div>;
  return (
    <Link
      to={stat.to}
      aria-label={`${stat.full ?? stat.value} ${stat.label}. Open the list.`}
      className={cn(frame, 'transition-colors hover:bg-hover active:bg-selected active:duration-0')}
    >
      {body}
    </Link>
  );
}

/** Four across on a wide screen, two on a laptop beside the sidebar, one on a phone. */
export function StatRow({ stats }: { stats: Stat[] }) {
  return (
    <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4">
      {stats.map((s) => <StatTile key={s.label} stat={s} />)}
    </div>
  );
}
