import { useEffect, useMemo, useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { exportReport, type ExportFormat } from '@/api/reporting';
import { PageHeader } from '@/components/PageHeader';
import { EmptyState, ErrorPanel, TableSkeleton } from '@/components/states';
import { Button } from '@/components/ui/button';
import { saveBlob } from '@/lib/download';
import { isInitialLoad } from '@/store/createResourceSlice';
import { useReportingStore } from '@/store/reportingStore';
import { ReportFilterBar } from './ReportFilterBar';
import { ReportSectionTable } from './ReportSectionTable';
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
        description={current?.filterSummary ?? definition.description}
        actions={
          <div className="flex gap-2">
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
        ) : result.status === 'error' && result.error ? (
          <ErrorPanel error={result.error} onRetry={() => params && void runReport(reportKey, params)} />
        ) : (
          current?.sections.map((section) => <ReportSectionTable key={section.title} section={section} />)
        )}
      </div>
    </>
  );
}
