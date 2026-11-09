import { ExternalLink, Search } from 'lucide-react';
import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { can } from '@/auth/access';
import { PageHeader } from '@/components/PageHeader';
import { ErrorPanel, TableSkeleton } from '@/components/states';
import { Input } from '@/components/ui/input';
import { isInitialLoad } from '@/store/createResourceSlice';
import { useReportingStore } from '@/store/reportingStore';
import { RebuildPanel } from './RebuildPanel';
import { ReportPage } from './ReportPage';
import { reportAreas, searchAreas } from './reportAreas';

/**
 * Reports (2026-11-09, the redesign; the layout rebuilt the same day after the user found it "not user friendly at
 * all"). One menu item. With no report open, an index of every report by area, searchable, compact enough to take in
 * at once. With a report open, the report has the whole width: the same list opens from its "All reports" button.
 */
export function ReportsPage() {
  const { reportKey } = useParams();
  const catalogue = useReportingStore((s) => s.catalogue);
  const loadCatalogue = useReportingStore((s) => s.loadCatalogue);
  const [query, setQuery] = useState('');

  useEffect(() => {
    if (catalogue.data === null && catalogue.status !== 'loading') void loadCatalogue();
  }, [catalogue.data, catalogue.status, loadCatalogue]);

  if (catalogue.status === 'error' && catalogue.data === null && catalogue.error) {
    return <div className="p-6"><ErrorPanel error={catalogue.error} onRetry={() => void loadCatalogue()} /></div>;
  }
  if (isInitialLoad(catalogue) || catalogue.data === null) return <div className="p-6"><TableSkeleton columns={3} /></div>;
  if (reportKey) return <ReportPage key={reportKey} />;

  const areas = searchAreas(reportAreas(catalogue.data), query);
  return (
    <>
      <PageHeader title="Reports" description="Every figure compares with the period before and opens to show how it was worked out." />
      <div className="space-y-6 px-6 pb-8">
        <label className="relative block max-w-md">
          <span className="sr-only">Search reports</span>
          <Search className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-muted-foreground" aria-hidden />
          <Input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Search reports" className="pl-9" />
        </label>

        {areas.length === 0 ? (
          <p className="text-sm text-muted-foreground">No report matches “{query.trim()}”.</p>
        ) : (
          <div className="space-y-6">
            {areas.map((area) => (
              <section key={area.id} aria-labelledby={`area-${area.id}`}>
                {/* The count beside the heading, not inside it: the area's name is the heading's whole name. */}
                <div className="mb-2 flex items-baseline gap-2">
                  <h2 id={`area-${area.id}`} className="font-display text-title font-semibold text-heading">{area.title}</h2>
                  <span className="text-xs text-muted-foreground">
                    {area.entries.length} {area.entries.length === 1 ? 'report' : 'reports'}
                  </span>
                </div>
                <ul className="grid gap-x-6 gap-y-1 rounded-xl border border-border bg-surface p-2 shadow-card sm:grid-cols-2 xl:grid-cols-3">
                  {area.entries.map((e) => (
                    <li key={e.key}>
                      {/* Named by its title alone, described by its line: a link found by name is found by the report's. */}
                      <Link
                        to={e.to ?? `/staff/reports/${e.key}`}
                        aria-label={e.to ? `${e.title} (opens its own page)` : e.title}
                        aria-describedby={`report-desc-${e.key}`}
                        className="group flex h-full flex-col rounded-lg px-3 py-2.5 hover:bg-hover focus-visible:bg-hover"
                      >
                        <span className="flex items-center gap-1.5 text-sm font-semibold text-foreground group-hover:underline">
                          {e.title}
                          {e.to && <ExternalLink className="size-3.5 text-muted-foreground" aria-hidden />}
                        </span>
                        {/* One line: the index is for finding, the report says the rest. */}
                        <span id={`report-desc-${e.key}`} className="truncate text-xs text-muted-foreground">{e.description}</span>
                      </Link>
                    </li>
                  ))}
                </ul>
              </section>
            ))}
          </div>
        )}

        {can('reports.rebuild') && <RebuildPanel />}
      </div>
    </>
  );
}
