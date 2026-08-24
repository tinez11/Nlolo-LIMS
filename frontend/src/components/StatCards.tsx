import { cn } from '@/lib/cn';
import { Spinner } from './states';

/**
 * The stat row.
 *
 * COUNTS ONLY, and deliberately no trend arrows. There are no analytics or
 * aggregate endpoints anywhere on this platform -- the only numbers obtainable are
 * `totalElements` from the four paged searches. A card reading "↗ 12% wk/wk" would
 * have nothing behind it, which is worse than no card at all.
 */

export interface Stat {
  label: string;
  /** null while the count is still loading. */
  value: number | null;
  /** What the number actually counts, so it cannot be misread. */
  hint: string;
  onSelect?: () => void;
  selected?: boolean;
}

export function StatCards({ stats }: { stats: Stat[] }) {
  return (
    <div className="grid grid-cols-2 gap-3 px-6 pb-5 lg:grid-cols-4">
      {stats.map((stat) => {
        const interactive = stat.onSelect !== undefined;
        const Tag = interactive ? 'button' : 'div';
        return (
          <Tag
            key={stat.label}
            {...(interactive ? { type: 'button' as const, onClick: stat.onSelect } : {})}
            aria-pressed={interactive ? stat.selected : undefined}
            className={cn(
              'rounded-lg border border-border bg-surface px-4 py-3 text-left transition-colors',
              interactive && 'hover:border-border-strong',
              stat.selected && 'border-border-strong bg-selected',
            )}
          >
            <p className="text-xs text-muted-foreground">{stat.label}</p>
            <p className="mt-1 text-2xl font-semibold tracking-tight">
              {stat.value === null ? (
                <Spinner className="my-1.5" />
              ) : (
                stat.value.toLocaleString()
              )}
            </p>
            <p className="mt-0.5 text-[11px] text-subtle-foreground">{stat.hint}</p>
          </Tag>
        );
      })}
    </div>
  );
}
