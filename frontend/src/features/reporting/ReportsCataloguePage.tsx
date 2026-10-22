import { useEffect, useState } from 'react';
import { useAuth } from 'react-oidc-context';
import { Link } from 'react-router-dom';
import { getRebuildStatus, startRebuild, type RebuildStatus, type ReportDefinition } from '@/api/reporting';
import { readIdentity } from '@/auth/claims';
import { PageHeader } from '@/components/PageHeader';
import { EmptyState, ErrorPanel, TableSkeleton } from '@/components/states';
import { Button } from '@/components/ui/button';
import { isInitialLoad } from '@/store/createResourceSlice';
import { useReportingStore } from '@/store/reportingStore';

const CATEGORY_LABELS: Record<ReportDefinition['category'], string> = {
  MANAGEMENT: 'Management',
  ACCOUNTING: 'Accounting',
};

/** Every report this tenant can run, grouped by the department that reads it. Management reports read a
 * projection of domain events; accounting reports read the general ledger itself. */
export function ReportsCataloguePage() {
  const auth = useAuth();
  const isAdmin = readIdentity(auth.user?.access_token).roles.includes('ADMIN');
  const catalogue = useReportingStore((s) => s.catalogue);
  const loadCatalogue = useReportingStore((s) => s.loadCatalogue);

  useEffect(() => {
    void loadCatalogue();
  }, [loadCatalogue]);

  function body() {
    if (isInitialLoad(catalogue)) return <TableSkeleton columns={2} />;
    if (catalogue.status === 'error' && catalogue.error && catalogue.data === null) {
      return <ErrorPanel error={catalogue.error} onRetry={() => void loadCatalogue()} />;
    }
    const reports = catalogue.data ?? [];
    if (reports.length === 0) return <EmptyState title="No reports" description="Nothing is available to run yet." />;
    const categories = [...new Set(reports.map((r) => r.category))];
    return categories.map((category) => (
      <section key={category} className="space-y-2">
        <h2 className="text-sm font-semibold">{CATEGORY_LABELS[category] ?? category}</h2>
        <ul className="divide-y divide-border rounded-xl border border-border bg-surface shadow-card">
          {reports
            .filter((r) => r.category === category)
            .map((r) => (
              <li key={r.key} className="px-4 py-3">
                <Link to={r.key} className="font-medium hover:underline">
                  {r.title}
                </Link>
                <p className="text-sm text-muted-foreground">{r.description}</p>
              </li>
            ))}
        </ul>
      </section>
    ));
  }

  return (
    <>
      <PageHeader title="Reports" description="Management and accounting reports, on screen or as Excel, CSV or PDF." />
      <div className="space-y-4 px-6 pb-6">
        {body()}
        {isAdmin && <RebuildPanel />}
      </div>
    </>
  );
}

/**
 * ADMIN only, matching POST /reports/rebuild. Needed once after the module is first deployed (the read model
 * starts empty) and whenever the failure metric shows events were missed. Status is fetched on request --
 * no polling timer for a job an administrator runs a few times a year.
 */
function RebuildPanel() {
  const [status, setStatus] = useState<RebuildStatus | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function act(action: () => Promise<RebuildStatus>) {
    setError(null);
    try {
      setStatus(await action());
    } catch {
      setError('The request failed. Try again in a moment.');
    }
  }

  return (
    <section className="space-y-2 rounded-xl border border-border bg-surface shadow-card p-3">
      <h2 className="text-sm font-semibold">Reporting data</h2>
      <p className="text-sm text-muted-foreground">
        Rebuild clears this organisation's reporting data and replays every recorded event. Reports read partial
        figures until it finishes.
      </p>
      <div className="flex gap-2">
        <Button size="sm" variant="secondary" onClick={() => void act(startRebuild)}>
          Rebuild reporting data
        </Button>
        <Button size="sm" variant="secondary" onClick={() => void act(getRebuildStatus)}>
          Check status
        </Button>
      </div>
      {status && (
        <p className="text-sm" role="status">
          {status.state} — {status.eventsReplayed} events replayed, {status.eventsFailed} failed
          {status.finishedAt ? `, finished ${status.finishedAt}` : ''}
        </p>
      )}
      {error && (
        <p role="alert" className="rounded-md bg-status-danger-bg px-3 py-2 text-sm text-status-danger-fg">
          {error}
        </p>
      )}
    </section>
  );
}
