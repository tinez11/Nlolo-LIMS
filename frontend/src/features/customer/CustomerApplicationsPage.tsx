import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { getCustomerApplications, type CustomerApplication } from '@/api/portal';
import { PageHeader } from '@/components/PageHeader';
import { EmptyState, ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { toApiError, type ApiError } from '@/lib/apiError';
import { formatDate } from '@/lib/dates';
import { cn } from '@/lib/cn';
import { ProgressLine } from './CustomerUi';
import { money } from './customerText';
import { progressOf } from './selfService';
import { useWords } from './words';

/**
 * What the customer has asked for (2026-10-08, the customer portal design step 5), each on the line it travels -- Sent,
 * Being reviewed, Decision, Pay first premium, Cover starts -- with what happens next in words (2026-10-29, the user:
 * "to see the progress of the application"). An accepted application becomes an offer in My policies.
 */
export function CustomerApplicationsPage() {
  const words = useWords();
  const w = words.progress;
  const [applications, setApplications] = useState<CustomerApplication[] | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [reload, setReload] = useState(0);

  useEffect(() => {
    let live = true;
    getCustomerApplications().then(
      (a) => { if (live) { setApplications(a); setError(null); } },
      (e: unknown) => { if (live) setError(toApiError(e)); },
    );
    return () => { live = false; };
  }, [reload]);

  const browse = <Button asChild variant="outline"><Link to="/customers/products">{w.getCover}</Link></Button>;
  return (
    <>
      <PageHeader title={w.title} description={w.lead} actions={browse} />
      <div className="w-full max-w-3xl space-y-4 px-4 pb-10 text-base sm:px-6">
        {error ? (
          <ErrorPanel error={error} onRetry={() => setReload((n) => n + 1)} />
        ) : !applications ? (
          <LoadingBlock label="Loading your applications" />
        ) : applications.length === 0 ? (
          <EmptyState title={w.empty} description={w.emptyLead} action={browse} />
        ) : (
          applications.map((a) => <ApplicationCard key={a.caseId} application={a} />)
        )}
      </div>
    </>
  );
}

function ApplicationCard({ application: a }: { application: CustomerApplication }) {
  const w = useWords().progress;
  const { at, tone } = progressOf(a);
  return (
    <article className="space-y-4 rounded-2xl border border-border bg-surface p-4 shadow-card sm:p-5">
      <header className="flex flex-wrap items-baseline justify-between gap-2">
        <div className="min-w-0">
          <h2 className="text-title">{a.productName ?? '—'}</h2>
          <p className="text-sm text-muted-foreground">
            {a.proposalNumber && <>{w.reference} <span className="font-mono">{a.proposalNumber}</span></>}
            {a.sumAssured != null && Number(a.sumAssured) > 0 && ` · ${money(a.sumAssured, a.currency)}`}
          </p>
        </div>
        {a.decidedAt && <p className="text-sm text-muted-foreground">{w.decided(formatDate(a.decidedAt.slice(0, 10)))}</p>}
      </header>
      <ProgressLine at={at} tone={tone} />
      <p className={cn('rounded-xl px-4 py-3',
        tone === 'stop' ? 'bg-status-danger-bg text-status-danger-fg'
          : tone === 'wait' ? 'bg-status-warning-bg text-status-warning-fg' : 'bg-control')}>
        {w.stage[a.stage] ?? a.statusText}
      </p>
      {(a.stage === 'ACCEPTED' || a.stage === 'ISSUED') && (
        <div className="flex flex-wrap gap-2">
          <Button asChild variant="primary" className="h-11"><Link to="/customers/pay">{w.pay}</Link></Button>
          <Button asChild variant="outline" className="h-11"><Link to="/customers/policies">{w.policies}</Link></Button>
        </div>
      )}
    </article>
  );
}
