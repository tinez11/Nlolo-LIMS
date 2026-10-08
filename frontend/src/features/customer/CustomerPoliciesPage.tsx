import { useEffect, useState } from 'react';
import { getCustomerDashboard, type CustomerPolicySummary } from '@/api/portal';
import { PageHeader } from '@/components/PageHeader';
import { Panel } from '@/components/Panel';
import { EmptyState, ErrorPanel, LoadingBlock } from '@/components/states';
import { toApiError, type ApiError } from '@/lib/apiError';
import { PolicyRow } from './CustomerHomePage';

/** Every policy the signed-in customer holds (2026-10-08, the customer portal step 2). */
export function CustomerPoliciesPage() {
  const [policies, setPolicies] = useState<CustomerPolicySummary[] | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [reload, setReload] = useState(0);

  useEffect(() => {
    let live = true;
    getCustomerDashboard().then(
      (d) => { if (live) { setPolicies(d.policies); setError(null); } },
      (e: unknown) => { if (live) setError(toApiError(e)); },
    );
    return () => { live = false; };
  }, [reload]);

  return (
    <>
      <PageHeader title="My policies" description="Every policy you hold with Nlolo Life." />
      <div className="px-4 pb-8 sm:px-6">
        {error ? <ErrorPanel error={error} onRetry={() => setReload((n) => n + 1)} />
          : policies === null ? <LoadingBlock label="Loading your policies" />
            : (
              <Panel title={`${policies.length} polic${policies.length === 1 ? 'y' : 'ies'}`}>
                {policies.length === 0
                  ? <EmptyState title="No policies yet" description="Policies you hold appear here once they are issued." />
                  : <ul className="divide-y divide-border">{policies.map((p) => <PolicyRow key={p.policyNumber} policy={p} />)}</ul>}
              </Panel>
            )}
      </div>
    </>
  );
}
