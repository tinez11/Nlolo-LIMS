import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { getCustomerDashboard, type CustomerPolicySummary } from '@/api/portal';
import { PageHeader } from '@/components/PageHeader';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { toApiError, type ApiError } from '@/lib/apiError';
import { PolicyCard } from './CustomerUi';
import { useWords } from './words';

/** Every policy the signed-in customer holds (2026-10-08, the customer portal step 2), as cards (2026-10-29). */
export function CustomerPoliciesPage() {
  const words = useWords();
  const w = words.portal.policies;
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
      <PageHeader title={w.title} description={w.lead} />
      <div className="w-full max-w-3xl space-y-3 px-4 pb-10 text-base sm:px-6">
        {error ? <ErrorPanel error={error} onRetry={() => setReload((n) => n + 1)} />
          : policies === null ? <LoadingBlock label="Loading your policies" />
            : policies.length === 0 ? (
              <div className="space-y-3 rounded-2xl border border-dashed border-border-strong bg-surface p-5 text-center">
                <p className="text-title">{words.portal.home.noPolicies}</p>
                <p className="text-muted-foreground">{words.portal.home.noPoliciesLead}</p>
                <Button asChild variant="primary" className="h-12 px-6 text-base"><Link to="/customers/products">{words.portal.home.getCover}</Link></Button>
              </div>
            ) : policies.map((p) => <PolicyCard key={p.policyNumber} policy={p} />)}
      </div>
    </>
  );
}
