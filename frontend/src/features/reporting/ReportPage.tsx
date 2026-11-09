import { TriangleAlert } from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { exportReport, type ExportFormat } from '@/api/reporting';
import { PageHeader } from '@/components/PageHeader';
import { Panel } from '@/components/Panel';
import { InlineError } from '@/components/InlineError';
import { EmptyState, ErrorPanel, TableSkeleton } from '@/components/states';
import { Button } from '@/components/ui/button';
import { saveBlob } from '@/lib/download';
import { isInitialLoad } from '@/store/createResourceSlice';
import { useReportingStore } from '@/store/reportingStore';
import { ReportFilterBar } from './ReportFilterBar';
import { ReportSectionTable } from './ReportSectionTable';
import { ReportFigures } from './ReportFigures';
import { ReportTree } from './ReportTree';
import { ReportSwitcher } from './ReportSwitcher';
import { ReportTrend } from './ReportTrend';
import { reportAreas } from './reportAreas';
import { BridgeChart } from '@/components/charts/BridgeChart';
import { stateFromSearch, toParams, validateFilters } from './reportParams';

/**
 * One page for every report. The filters live in the address bar, so a report can be bookmarked or sent to
 * a colleague exactly as it was run; the page runs the report whenever the address changes.
 */
export function ReportPage() {
  const { reportKey = '' } = useParams();
  const [search, setSearch] = useSearchParams();
  const catalogue = useReportingStore((s) => s.catalogue);
  const loadCatalogue = useReportingStore((s) => s.loadCatalogue);
  const result = useReportingStore((s) => s.result);
  const runReport = useReportingStore((s) => s.runReport);
  const [exportError, setExportError] = useState<string | null>(null);

  useEffect(() => {
    if (catalogue.data === null && catalogue.status !== 'loading') void loadCatalogue();
  }, [catalogue.data, catalogue.status, loadCatalogue]);

  const definition = catalogue.data?.find((d) => d.key === reportKey) ?? null;
  const today = useMemo(() => new Date(), []);
  const filters = definition ? stateFromSearch(definition, search, today) : null;
  const params = definition && filters ? toParams(definition, filters) : null;
  const paramsKey = params ? new URLSearchParams(params).toString() : '';

  useEffect(() => {
    if (!definition || !filters || validateFilters(definition, filters)) return;
    void runReport(reportKey, toParams(definition, filters));
    // paramsKey is the whole filter state as a string; depending on the objects would rerun every render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [reportKey, paramsKey, definition?.key]);

  async function download(format: ExportFormat) {
    if (!params) return;
    setExportError(null);
    try {
      const blob = await exportReport(reportKey, params, format);
      const stamp = new Date().toISOString().slice(0, 10);
      saveBlob(blob, `${reportKey}-${stamp}.${format}`);
    } catch {
      setExportError('The file could not be produced. Run the report again, then retry.');
    }
  }

  if (catalogue.status === 'error' && catalogue.data === null && catalogue.error) {
    return <ErrorPanel error={catalogue.error} onRetry={() => void loadCatalogue()} />;
  }
  if (!definition) {
    return catalogue.data === null ? (
      <TableSkeleton columns={4} />
    ) : (
      <EmptyState title="No such report" action={<Link to="../reports">Back to all reports</Link>} />
    );
  }

  const current = result.data && result.data.key === reportKey ? result.data : null;
  return (
    <>
      <PageHeader
        title={definition.title}
        breadcrumb={[{ label: 'Reports', to: '/staff/reports' }]}
        description={current ? [current.filterSummary, current.comparisonLabel].filter(Boolean).join(' · ') : definition.description}
        actions={
          <div className="flex flex-wrap gap-2">
            {catalogue.data && <ReportSwitcher areas={reportAreas(catalogue.data)} currentKey={reportKey} />}
            {definition.category === 'SOLVENCY' && (
              <Button asChild size="sm" variant="secondary">
                <Link to="/staff/solvency-factors">Factors</Link>
              </Button>
            )}
            {(['xlsx', 'csv', 'pdf'] as const).map((f) => (
              <Button key={f} size="sm" variant="secondary" onClick={() => void download(f)} disabled={!current}>
                {f === 'xlsx' ? 'Excel' : f.toUpperCase()}
              </Button>
            ))}
          </div>
        }
      />
      <div className="space-y-4 px-6 pb-6">
        <ReportFilterBar
          key={reportKey}
          definition={definition}
          initial={filters ?? stateFromSearch(definition, search, today)}
          onRun={(state) => setSearch(toParams(definition, state))}
        />
        {exportError && (
          <p role="alert" className="rounded-md bg-status-danger-bg px-3 py-2 text-sm text-status-danger-fg">
            {exportError}
          </p>
        )}
        {isInitialLoad(result) || (result.status === 'loading' && !current) ? (
          <TableSkeleton columns={5} />
        ) : result.status === 'error' && result.error?.kind === 'validation' ? (
          /*
            A parameter the server refused is a question about the form above, not a failed read. It renders
            beside that form, in the server's own words, and offers no "Try again" -- retrying an unchanged
            submission fails identically, and a button that cannot work is worse than no button.
          */
          <InlineError lead="The report could not run" error={result.error} />
        ) : result.status === 'error' && result.error ? (
          <ErrorPanel error={result.error} onRetry={() => params && void runReport(reportKey, params)} />
        ) : current && params ? (
          <>
            {/* What a reader must know before trusting the figures -- placeholder factors, an approximation -- above
                them, never in a footnote (2026-11-09, the solvency reports). */}
            {current.notices.length > 0 && (
              <ul className="space-y-1.5" aria-label="Before you read these figures">
                {current.notices.map((n) => (
                  <li key={n} className="flex items-start gap-2 rounded-lg bg-status-warning-bg px-3 py-2 text-sm text-status-warning-fg">
                    <TriangleAlert className="mt-0.5 size-4 shrink-0" aria-hidden />
                    <span>{n}</span>
                  </li>
                ))}
              </ul>
            )}
            <ReportFigures figures={current.figures} comparisonLabel={current.comparisonLabel} />
            {current.chart && current.chart.kind === 'BRIDGE' && current.chart.bars.length > 0 && (
              <Panel title={current.chart.description}>
                <div className="px-4 pb-4">
                  <BridgeChart bars={current.chart.bars} description={current.chart.description} unit={current.chart.unit} />
                </div>
              </Panel>
            )}
            {current.chart && current.chart.kind === 'TREND' && current.chart.series.length > 0 && (
              <Panel title={current.chart.description}>
                <div className="px-4 pb-4">
                  <ReportTrend chart={current.chart} />
                </div>
              </Panel>
            )}
            {/* A section whose lines open to what they are made of is a tree (2026-11-09); the rest stay tables. */}
            {current.sections.map((section) =>
              section.rows.some((r) => r['_lineId']) ? (
                <ReportTree key={section.title} reportKey={reportKey} params={params} section={section} />
              ) : (
                <ReportSectionTable key={section.title} section={section} />
              ),
            )}
          </>
        ) : null}
      </div>
    </>
  );
}
