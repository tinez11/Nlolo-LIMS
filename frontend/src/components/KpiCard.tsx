import { ArrowDown, ArrowUp } from 'lucide-react';
import { Link } from 'react-router-dom';
import { cn } from '@/lib/cn';

/**
 * One dashboard figure (2026-10-14).
 *
 * <b>A link to exactly what it counted, or not a link.</b> `to` is the list or report the figure was read from,
 * already filtered, so the rows a person lands on are the rows the number promised. A figure whose destination cannot
 * reproduce its filter has no `to`, and is drawn as a card that is not clickable.
 *
 * <b>Never wraps.</b> The figure is `whitespace-nowrap`, and the card is a size container: it is set in the Display
 * tier when the card is wide enough and steps down to the Headline tier when it is not. Both are tiers already on the
 * page (the stat figure and the page title), so stepping down adds no third size (DESIGN.md, the Two-Peaks Rule).
 *
 * <b>A delta is ink.</b> Up is a direction, not a verdict (the Stamp Rule).
 */
export interface Kpi {
  label: string;
  /** Already formatted -- a count from `countValue`, or money from `formatMoney`. Null = could not be read. */
  value: string | null;
  hint: string;
  to?: string;
  delta?: { direction: 'up' | 'down'; text: string };
}

function Body({ kpi }: { kpi: Kpi }) {
  return (
    <>
      <span className="block text-xs font-medium text-muted-foreground">{kpi.label}</span>
      {kpi.value === null ? (
        // The dash is decoration; the words below it say what it means, once, to everyone.
        <span className="mt-1 block text-headline text-subtle-foreground" aria-hidden>
          —
        </span>
      ) : (
        <span className="mt-1 block whitespace-nowrap text-headline @[20rem]:text-display">{kpi.value}</span>
      )}
      {kpi.delta && kpi.value !== null && (
        <span data-delta className="mt-0.5 flex items-center gap-1 text-xs text-foreground">
          {kpi.delta.direction === 'up' ? (
            <ArrowUp className="size-3" aria-hidden />
          ) : (
            <ArrowDown className="size-3" aria-hidden />
          )}
          <span>{kpi.delta.text}</span>
        </span>
      )}
      <span className="mt-0.5 block text-xs text-subtle-foreground">
        {kpi.value === null ? 'Not available' : kpi.hint}
      </span>
    </>
  );
}

function KpiCard({ kpi }: { kpi: Kpi }) {
  const frame = '@container block min-w-0 rounded-lg border border-border bg-surface px-4 py-3';
  // A failed figure is never a link: it would open a list whose count contradicts the "Not available" on the card.
  if (!kpi.to || kpi.value === null) {
    return (
      <div className={frame}>
        <Body kpi={kpi} />
      </div>
    );
  }
  return (
    <Link
      to={kpi.to}
      aria-label={`${kpi.value} ${kpi.label}, ${kpi.hint}. Open the list.`}
      className={cn(
        frame,
        'transition-colors hover:border-border-strong hover:bg-hover active:bg-selected active:duration-0',
      )}
    >
      <Body kpi={kpi} />
    </Link>
  );
}

/**
 * The row. `auto-fill` with a 16rem floor lays out only as many columns as can hold a full TZS amount, so a narrow
 * screen gets fewer cards across rather than squeezed figures.
 */
export function KpiRow({ kpis }: { kpis: Kpi[] }) {
  return (
    <div className="grid grid-cols-[repeat(auto-fill,minmax(16rem,1fr))] gap-3">
      {kpis.map((kpi) => (
        <KpiCard key={kpi.label} kpi={kpi} />
      ))}
    </div>
  );
}
