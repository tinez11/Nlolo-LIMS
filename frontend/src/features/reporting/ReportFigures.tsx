import { ArrowDownRight, ArrowUpRight } from 'lucide-react';
import type { ReportFigure } from '@/api/reporting';
import { cn } from '@/lib/cn';
import { formatCell } from './reportParams';
import { changeWords, isGood } from './figureWords';

/**
 * The answer a report exists for, on the figures band, and the breakdown figures beneath it as tiles (2026-11-09,
 * DESIGN.md "The Lit Figure"). The band holds figures only, never controls; every figure is the server's.
 */
export function ReportFigures({ figures, comparisonLabel }: { figures: ReportFigure[]; comparisonLabel?: string | null | undefined }) {
  const lead = figures.filter((f) => f.lead);
  const tiles = figures.filter((f) => !f.lead);
  if (figures.length === 0) return null;
  return (
    <div className="space-y-3">
      {lead.length > 0 && (
        <dl className="hero-scope grid grid-cols-1 gap-x-8 gap-y-4 rounded-xl bg-hero p-5 sm:grid-cols-3" aria-label="Headline figures">
          {lead.map((f) => {
            const words = changeWords(f);
            return (
              <div key={f.key} className="min-w-0">
                <dt className="text-xs text-muted-foreground">{f.label}</dt>
                <dd className="mt-1 text-display whitespace-nowrap">{formatCell('MONEY', f.value)}</dd>
                {words && <dd className="mt-1 text-xs text-muted-foreground">{words}</dd>}
              </div>
            );
          })}
        </dl>
      )}
      {comparisonLabel && lead.length === 0 && <p className="text-xs text-muted-foreground">{comparisonLabel}</p>}
      {tiles.length > 0 && (
        <ul className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4">
          {tiles.map((f) => {
            const good = isGood(f);
            const down = f.change?.startsWith('-') ?? false;
            return (
              <li key={f.key} className="rounded-xl border border-border bg-surface p-4 shadow-card">
                <p className="text-xs font-semibold text-muted-foreground">{f.label}</p>
                <p className="mt-2 text-figure whitespace-nowrap">{formatCell('MONEY', f.value)}</p>
                {changeWords(f) && (
                  <p
                    data-delta={good === null ? 'none' : good ? 'good' : 'bad'}
                    className={cn(
                      'mt-2 inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-medium',
                      good === null ? 'bg-control text-muted-foreground'
                        : good ? 'bg-status-success-bg text-status-success-fg' : 'bg-status-danger-bg text-status-danger-fg',
                    )}
                  >
                    {down ? <ArrowDownRight className="size-3.5" aria-hidden /> : <ArrowUpRight className="size-3.5" aria-hidden />}
                    {changeWords(f)}
                  </p>
                )}
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
