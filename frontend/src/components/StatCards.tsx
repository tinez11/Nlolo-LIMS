import { cn } from '@/lib/cn';
import { Spinner } from './states';

/**
 * The stat row, and the single count that replaced it on nine screens.
 *
 * COUNTS ONLY, and deliberately no trend arrows. There are no analytics or
 * aggregate endpoints anywhere on this platform -- the only numbers obtainable are
 * `totalElements` from the four paged searches. A card reading "↗ 12% wk/wk" would
 * have nothing behind it, which is worse than no card at all.
 */

export interface Stat {
  /**
   * Phrased to follow a number: `clients`, `pending claims`, `journal entries`. It
   * used to be a card caption sitting ABOVE the figure ("All clients"), which is
   * why the wording changed when the single-count screens moved it inline.
   */
  label: string;
  /** null while there is no number to show yet -- see `pending` for why. */
  value: number | null;
  /**
   * True while a value is actively expected soon (spinner). False with a null
   * value means the load genuinely failed and nothing is in flight to fix that --
   * rendered as a dash, never a spinner that spins forever. Getting this wrong
   * once meant a failed initial fetch left every card spinning permanently, next
   * to an error panel telling the user the load had already finished failing.
   */
  pending?: boolean;
  /** What the number actually counts, so it cannot be misread. */
  hint: string;
  onSelect?: () => void;
  selected?: boolean;
}

/**
 * One count, inline in the page header.
 *
 * Nine of the ten screens with a stat row passed exactly ONE stat into a
 * four-column grid: a 282px card in a 1216px row, three quarters of it empty,
 * holding 130px of the first screenful to restate the number the pager already
 * prints under the table ("1–20 of 67"). A row of cards is for numbers a person
 * compares against each other; a single number is a fact about the screen, and it
 * belongs in the sentence under its title.
 *
 * The three states of `Stat` survive the move intact, because they are the honest
 * part: a spinner only while a value is genuinely in flight, an em dash labelled
 * for screen readers when the load has already failed, and a hint that always says
 * what was counted. No live region -- the pager below is already
 * `aria-live="polite"` with the same total in it, and two regions announcing one
 * number is worse than one.
 */
export function CountLine({ label, value, pending, hint }: Stat) {
  return (
    <>
      <span className="font-medium text-foreground tabular-nums">
        {value !== null ? (
          value.toLocaleString()
        ) : pending ? (
          <Spinner />
        ) : (
          <span className="text-subtle-foreground" aria-label="Not available">
            —
          </span>
        )}
      </span>{' '}
      {label} <span className="text-subtle-foreground">· {hint}</span>
    </>
  );
}

/**
 * A row of counts, for the screens where there is genuinely more than one and the
 * comparison between them is the point.
 *
 * Sized to its content rather than to a fixed four-column grid. The grid was
 * `grid-cols-2 lg:grid-cols-4` whatever it was given, so two stats meant two 600px
 * cards each holding one number, and one stat meant three empty columns.
 */
export function StatCards({ stats }: { stats: Stat[] }) {
  if (stats.length === 0) return null;

  return (
    <div className="flex flex-wrap gap-3 px-6 pb-5">
      {stats.map((stat) => {
        const interactive = stat.onSelect !== undefined;
        const Tag = interactive ? 'button' : 'div';
        return (
          <Tag
            key={stat.label}
            {...(interactive ? { type: 'button' as const, onClick: stat.onSelect } : {})}
            aria-pressed={interactive ? stat.selected : undefined}
            className={cn(
              'min-w-36 flex-1 basis-0 rounded-lg border border-border bg-surface px-4 py-3 text-left transition-colors sm:max-w-60',
              interactive && 'hover:border-border-strong',
              stat.selected && 'border-border-strong bg-selected',
            )}
          >
            <p className="text-xs text-muted-foreground">{stat.label}</p>
            <p className="mt-1 text-2xl font-semibold tracking-tight">
              {stat.value !== null ? (
                stat.value.toLocaleString()
              ) : stat.pending ? (
                <Spinner className="my-1.5" />
              ) : (
                <span className="text-subtle-foreground" aria-label="Not available">
                  —
                </span>
              )}
            </p>
            <p className="mt-0.5 text-xs text-subtle-foreground">{stat.hint}</p>
          </Tag>
        );
      })}
    </div>
  );
}
