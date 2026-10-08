import { ChevronRight, Plus } from 'lucide-react';
import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { getCustomerClaims, type CustomerClaimSummary } from '@/api/portal';
import { PageHeader } from '@/components/PageHeader';
import { Panel } from '@/components/Panel';
import { EmptyState, ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { toApiError, type ApiError } from '@/lib/apiError';
import { formatDate } from '@/lib/dates';
import { claimTypeText } from './customerText';

/**
 * The claims the customer made (2026-10-08, the customer portal design step 4; PRD §17-21), newest event first. A claim
 * with a document still asked for says so on its row -- that is the one thing on this page the customer must act on.
 */
export function CustomerClaimsPage() {
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
    <Button asChild variant="primary">
      <Link to="/customers/claims/new"><Plus aria-hidden /> Report a claim</Link>
    </Button>
  );
  return (
    <>
      <PageHeader title="My claims" description="Claims you have made, and where each one is." actions={report} />
      <div className="px-4 pb-8 sm:px-6">
        {error ? (
          <ErrorPanel error={error} onRetry={() => setReload((n) => n + 1)} />
        ) : !claims ? (
          <LoadingBlock label="Loading your claims" />
        ) : (
          <Panel title="Claims">
            {claims.length === 0 ? (
              <EmptyState title="No claims" description="When you report a claim it appears here, with each step as it happens." />
            ) : (
              <ul className="divide-y divide-border">
                {claims.map((c) => (
                  <li key={c.claimId}>
                    <Link to={`/customers/claims/${c.claimId}`}
                      className="flex items-center justify-between gap-3 px-4 py-3 hover:bg-hover">
                      <span className="min-w-0">
                        <span className="block text-sm font-medium">
                          {claimTypeText(c.claimType)} · {c.productName ?? c.policyNumber}
                        </span>
                        <span className="block text-xs text-muted-foreground">
                          {formatDate(c.dateOfEvent)} · <span className="font-mono">{c.policyNumber}</span>
                        </span>
                        {c.actionsRequired > 0 && (
                          <span className="mt-0.5 block text-xs font-medium text-status-warning-fg">
                            {c.actionsRequired === 1 ? 'A document is needed from you' : `${c.actionsRequired} documents are needed from you`}
                          </span>
                        )}
                      </span>
                      <span className="flex shrink-0 items-center gap-1 text-sm">
                        {c.statusText}
                        <ChevronRight className="size-4 text-muted-foreground" aria-hidden />
                      </span>
                    </Link>
                  </li>
                ))}
              </ul>
            )}
          </Panel>
        )}
      </div>
    </>
  );
}
