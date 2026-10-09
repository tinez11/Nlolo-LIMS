import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { getCustomerApplications, type CustomerApplication } from '@/api/portal';
import { PageHeader } from '@/components/PageHeader';
import { Panel } from '@/components/Panel';
import { EmptyState, ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { toApiError, type ApiError } from '@/lib/apiError';
import { money } from './customerText';

/**
 * What the customer has asked for (2026-10-08, the customer portal design step 5), each with where it is in words a
 * customer reads. An accepted application becomes an offer in My policies, awaiting its first premium.
 */
export function CustomerApplicationsPage() {
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

  const browse = <Button asChild variant="outline"><Link to="/customers/products">See products</Link></Button>;
  return (
    <>
      <PageHeader title="My applications" description="Cover you have asked for, and where each request is." actions={browse} />
      <div className="px-4 pb-8 sm:px-6">
        {error ? (
          <ErrorPanel error={error} onRetry={() => setReload((n) => n + 1)} />
        ) : !applications ? (
          <LoadingBlock label="Loading your applications" />
        ) : (
          <Panel title="Applications">
            {applications.length === 0 ? (
              <EmptyState title="No applications" description="When you ask for cover it appears here." />
            ) : (
              <ul className="divide-y divide-border">
                {applications.map((a) => (
                  <li key={a.caseId} className="flex flex-wrap items-center justify-between gap-2 px-4 py-3">
                    <span className="min-w-0">
                      <span className="block text-sm font-medium">{a.productName ?? 'Cover'}</span>
                      <span className="block text-xs text-muted-foreground">
                        {a.proposalNumber && <span className="font-mono">{a.proposalNumber}</span>}
                        {a.sumAssured != null && Number(a.sumAssured) > 0 && ` · ${money(a.sumAssured, a.currency)} cover`}
                      </span>
                    </span>
                    <span className="text-sm">{a.statusText}</span>
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
