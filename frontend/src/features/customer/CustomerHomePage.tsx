import { useEffect, useState } from 'react';
import { getMe, type CustomerMe } from '@/api/portal';
import { searchPolicies } from '@/api/policies';
import type { PolicyView } from '@/api/types';
import { PageHeader } from '@/components/PageHeader';
import { Panel } from '@/components/Panel';
import { StatusBadge } from '@/components/StatusBadge';
import { EmptyState, ErrorPanel, LoadingBlock } from '@/components/states';
import { toApiError, type ApiError } from '@/lib/apiError';
import { formatMoney } from '@/lib/money';
import { humanizeStatus } from '@/lib/status';

/**
 * The signed-in customer's home (2026-10-08, the customer portal step 1): who they are, and the policies they hold.
 * The server decides both from the token alone -- `/customer/me` from its party_id, and `/policies` force-scoped to
 * the policyholder -- so nothing here chooses whose records are shown. The dashboard proper is step 2.
 */
export function CustomerHomePage() {
  const [me, setMe] = useState<CustomerMe | null>(null);
  const [policies, setPolicies] = useState<PolicyView[] | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [reload, setReload] = useState(0);

  useEffect(() => {
    let live = true;
    Promise.all([getMe(), searchPolicies({ pageSize: 50 })]).then(
      ([m, page]) => { if (live) { setMe(m); setPolicies(page.items); setError(null); } },
      (e: unknown) => { if (live) setError(toApiError(e)); },
    );
    return () => { live = false; };
  }, [reload]);

  if (error) {
    return (
      <div className="px-4 pt-6 sm:px-6">
        <ErrorPanel error={error} onRetry={() => setReload((n) => n + 1)} />
      </div>
    );
  }
  if (!me || !policies) return <LoadingBlock label="Loading your account" />;

  return (
    <>
      <PageHeader title={`Welcome, ${me.displayName.split(' ')[0]}`} description="Your policies with Nlolo Life." />
      <div className="px-4 pb-8 sm:px-6">
        <Panel title="My policies" subtitle={`${policies.length} polic${policies.length === 1 ? 'y' : 'ies'}`}>
          {policies.length === 0 ? (
            <EmptyState title="No policies yet" description="Policies you hold will appear here once they are issued." />
          ) : (
            <ul className="divide-y divide-border">
              {policies.map((p) => (
                <li key={p.policyNumber} className="flex flex-wrap items-center justify-between gap-2 px-4 py-3">
                  <div className="min-w-0">
                    <p className="text-sm font-medium">{p.productCategory ? humanizeStatus(p.productCategory) : 'Policy'}</p>
                    <p className="font-mono text-xs text-muted-foreground">{p.policyNumber}</p>
                  </div>
                  <div className="flex items-center gap-3 text-sm">
                    {p.premium && <span>{formatMoney(p.premium)} {frequencyLabel(p.premiumFrequency)}</span>}
                    {p.status && <StatusBadge kind="policy" value={p.status} />}
                  </div>
                </li>
              ))}
            </ul>
          )}
        </Panel>
      </div>
    </>
  );
}

function frequencyLabel(frequency: string | null | undefined): string {
  switch (frequency) {
    case 'MONTHLY': return 'a month';
    case 'QUARTERLY': return 'a quarter';
    case 'ANNUALLY': return 'a year';
    case 'SINGLE': return 'once';
    default: return '';
  }
}
