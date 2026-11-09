import { ChevronDown, ChevronRight, ExternalLink, Search } from 'lucide-react';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Input } from '@/components/ui/input';
import { cn } from '@/lib/cn';
import { searchAreas, type ReportArea } from './reportAreas';

/**
 * The reports, inside the Reports page rather than the main menu (2026-11-09, the user: one menu item, the areas
 * inside). Every area starts closed and the open report's area opens itself; a search opens every area it matches.
 */
export function ReportList({ areas, currentKey }: { areas: ReportArea[]; currentKey: string | null }) {
  const [query, setQuery] = useState('');
  const currentArea = areas.find((a) => a.entries.some((e) => e.key === currentKey))?.id ?? null;
  const [open, setOpen] = useState<Record<string, boolean>>({});
  const searching = query.trim() !== '';
  const shown = searchAreas(areas, query);

  return (
    <nav aria-label="Reports" className="space-y-2">
      <label className="relative block">
        <span className="sr-only">Search reports</span>
        <Search className="pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2 text-muted-foreground" aria-hidden />
        <Input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Search reports" className="pl-8" />
      </label>
      {shown.length === 0 && <p className="px-2 py-3 text-sm text-muted-foreground">No report matches “{query.trim()}”.</p>}
      <ul className="space-y-0.5">
        {shown.map((area) => {
          const expanded = searching || (open[area.id] ?? area.id === currentArea);
          return (
            <li key={area.id}>
              <button
                type="button"
                aria-expanded={expanded}
                onClick={() => setOpen((o) => ({ ...o, [area.id]: !expanded }))}
                className="flex w-full items-center gap-1.5 rounded-md px-2 py-2 text-left text-sm font-semibold hover:bg-hover"
              >
                {expanded ? <ChevronDown className="size-4" aria-hidden /> : <ChevronRight className="size-4" aria-hidden />}
                <span className="flex-1">{area.title}</span>
                <span className="text-xs font-normal text-muted-foreground">{area.entries.length}</span>
              </button>
              {expanded && (
                <ul className="mb-1 ml-5 space-y-0.5 border-l border-border pl-2">
                  {area.entries.map((entry) => (
                    <li key={entry.key}>
                      <Link
                        to={entry.to ?? `/staff/reports/${entry.key}`}
                        aria-current={entry.key === currentKey ? 'page' : undefined}
                        className={cn(
                          'flex items-center gap-1.5 rounded-md px-2 py-1.5 text-sm hover:bg-hover',
                          entry.key === currentKey && 'bg-selected font-semibold',
                        )}
                      >
                        <span className="flex-1">{entry.title}</span>
                        {entry.to && <ExternalLink className="size-3.5 text-muted-foreground" aria-label="opens its own page" />}
                      </Link>
                    </li>
                  ))}
                </ul>
              )}
            </li>
          );
        })}
      </ul>
    </nav>
  );
}
