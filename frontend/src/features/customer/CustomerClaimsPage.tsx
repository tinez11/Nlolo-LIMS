import { ChevronRight, FileWarning, Plus } from 'lucide-react';
import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { getCustomerClaims, type CustomerClaimSummary } from '@/api/portal';
import { PageHeader } from '@/components/PageHeader';
import { EmptyState, ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { toApiError, type ApiError } from '@/lib/apiError';
import { formatDate } from '@/lib/dates';
import { useWords } from './words';

/**
 * The claims the customer made (2026-10-08, the customer portal design step 4; PRD §17-21), newest event first, as cards
 * (2026-10-29). A claim with a document still asked for says so in a tinted line -- the one thing here the customer
 * must act on.
 */
export function CustomerClaimsPage() {
  const words = useWords().portal;
  const w = words.claims;
  const [claims, setClaims] = useState<CustomerClaimSummary[] | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [reload, setReload] = useState(0);

  useEffect(() => {
    let live = true;
    getCustomerClaims().then(
      (c) => { if (live) { setClaims(c); setError(null); } },
      (e: unknown) => { if (live) setError(toApiError(e)); },
    );
    return () => { live = false; };
  }, [reload]);

  const report = (
    <Button asChild variant="primary" className="h-11">
      <Link to="/customers/claims/new"><Plus aria-hidden /> {w.report}</Link>
    </Button>
  );
  return (
    <>
      <PageHeader title={w.title} description={w.lead} actions={report} />
      <div className="w-full max-w-3xl space-y-3 px-4 pb-10 text-base sm:px-6">
        {error ? (
          <ErrorPanel error={error} onRetry={() => setReload((n) => n + 1)} />
        ) : !claims ? (
          <LoadingBlock label="Loading your claims" />
        ) : claims.length === 0 ? (
          <EmptyState title={w.none} description={w.noneLead} />
        ) : (
          claims.map((c) => (
            <Link key={c.claimId} to={`/customers/claims/${c.claimId}`}
              className="flex items-center gap-3 rounded-2xl border border-border bg-surface p-4 shadow-card hover:bg-hover">
              <span className="min-w-0 flex-1 space-y-1">
                <span className="block text-title">{words.claimType[c.claimType] ?? c.claimType} · {c.productName ?? c.policyNumber}</span>
                <span className="block text-sm text-muted-foreground">
                  {formatDate(c.dateOfEvent)} · <span className="font-mono text-xs">{c.policyNumber}</span>
                </span>
                <span className="inline-block rounded-full bg-control px-2.5 py-0.5 text-sm font-medium">
                  {words.claimStatus[c.status] ?? c.statusText}
                </span>
                {c.actionsRequired > 0 && (
                  <span className="flex items-center gap-2 rounded-lg bg-status-warning-bg px-3 py-1.5 text-sm font-semibold text-status-warning-fg">
                    <FileWarning className="size-4 shrink-0" aria-hidden />{w.docs(c.actionsRequired)}
                  </span>
                )}
              </span>
              <ChevronRight className="size-5 shrink-0 text-muted-foreground" aria-hidden />
            </Link>
          ))
        )}
      </div>
    </>
  );
}
