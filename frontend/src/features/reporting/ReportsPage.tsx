import { useEffect } from 'react';
import { Link, useParams } from 'react-router-dom';
import { can } from '@/auth/access';
import { PageHeader } from '@/components/PageHeader';
import { ErrorPanel, TableSkeleton } from '@/components/states';
import { isInitialLoad } from '@/store/createResourceSlice';
import { useReportingStore } from '@/store/reportingStore';
import { RebuildPanel } from './RebuildPanel';
import { ReportList } from './ReportList';
import { ReportPage } from './ReportPage';
import { reportAreas } from './reportAreas';

/**
 * Reports (2026-11-09, the redesign): one item in the main menu; inside, a searchable list of every report by area
 * beside the report that is open. With no report open, each area and what it holds.
 */
export function ReportsPage() {
  const { reportKey } = useParams();
  const catalogue = useReportingStore((s) => s.catalogue);
  const loadCatalogue = useReportingStore((s) => s.loadCatalogue);

  useEffect(() => {
    if (catalogue.data === null && catalogue.status !== 'loading') void loadCatalogue();
  }, [catalogue.data, catalogue.status, loadCatalogue]);

  if (catalogue.status === 'error' && catalogue.data === null && catalogue.error) {
    return <div className="p-6"><ErrorPanel error={catalogue.error} onRetry={() => void loadCatalogue()} /></div>;
  }
  if (isInitialLoad(catalogue) || catalogue.data === null) return <div className="p-6"><TableSkeleton columns={3} /></div>;

  const areas = reportAreas(catalogue.data);
  return (
    <div className="flex min-h-full flex-col lg:flex-row">
      <aside className="border-b border-border bg-surface px-4 py-4 lg:sticky lg:top-0 lg:max-h-screen lg:w-64 lg:shrink-0 lg:overflow-y-auto lg:border-r lg:border-b-0">
        <ReportList areas={areas} currentKey={reportKey ?? null} />
      </aside>
      <div className="min-w-0 flex-1">
        {reportKey ? (
          <ReportPage key={reportKey} />
        ) : (
          <>
            <PageHeader title="Reports" description="Every figure compares with the period before, and opens to show how it was worked out." />
            <div className="space-y-4 px-6 pb-6">
              <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
                {areas.map((area) => (
                  <section key={area.id} className="rounded-xl border border-border bg-surface p-4 shadow-card">
                    <h2 className="font-display text-sm font-semibold text-heading">{area.title}</h2>
                    <ul className="mt-2 space-y-2">
                      {area.entries.map((e) => (
                        <li key={e.key}>
                          <Link to={e.to ?? `/staff/reports/${e.key}`} className="text-sm font-medium hover:underline">{e.title}</Link>
                          <p className="text-xs text-muted-foreground">{e.description}</p>
                        </li>
                      ))}
                    </ul>
                  </section>
                ))}
              </div>
              {can('reports.rebuild') && <RebuildPanel />}
            </div>
          </>
        )}
      </div>
    </div>
  );
}
