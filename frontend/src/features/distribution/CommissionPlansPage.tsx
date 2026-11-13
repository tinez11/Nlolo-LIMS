import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { useAuth } from 'react-oidc-context';
import type { CommissionPlanView } from '@/api/types';
import { can } from '@/auth/access';
import { readIdentity } from '@/auth/claims';
import { FormField } from '@/components/FormField';
import { InlineError } from '@/components/InlineError';
import { PageHeader } from '@/components/PageHeader';
import { StaffName } from '@/components/StaffName';
import { EmptyState, ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { formatInstantDay } from '@/lib/dates';
import { barredAsOwn, mayActAlone, SIGNING_OWN } from '@/lib/ownWork';
import { isInitialLoad } from '@/store/createResourceSlice';
import { useDistributionStore } from '@/store/distributionStore';
import { describeRules } from './commissionRules';

/**
 * Commission plans and agents' own rates waiting for a second person (2026-11-12, finance finding 190: one person
 * could set what every agent is paid). A plan earns nothing until approved. Its proposer approves it only holding
 * commission.approve-own, given in Roles and permissions; anyone may withdraw their own.
 */
export function CommissionPlansPage() {
  const proposed = useDistributionStore((s) => s.proposedPlans);
  const load = useDistributionStore((s) => s.loadProposedPlans);
  const viewer = readIdentity(useAuth().user?.access_token)?.subject ?? undefined;

  useEffect(() => {
    void load();
  }, [load]);

  function renderList() {
    if (isInitialLoad(proposed)) return <LoadingBlock />;
    if (proposed.status === 'error' && proposed.error && proposed.data === null) {
      return <ErrorPanel error={proposed.error} onRetry={() => void load()} />;
    }
    const rows = proposed.data ?? [];
    if (rows.length === 0) {
      return <EmptyState title="Nothing waiting" description="Plans proposed on an agent's page wait here for approval." />;
    }
    return (
      <div className="divide-y divide-border rounded-md border border-border" role="list" aria-label="Plans waiting">
        {rows.map((plan) => (
          <PlanRow key={plan.planId} plan={plan} viewer={viewer} />
        ))}
      </div>
    );
  }

  return (
    <>
      <PageHeader title="Commission plans" description="A plan earns nothing until a second person approves it." />
      <div className="px-6 pb-6">
        <div className="rounded-xl border border-border bg-surface p-4 shadow-card">{renderList()}</div>
      </div>
    </>
  );
}

function PlanRow({ plan, viewer }: { plan: CommissionPlanView; viewer: string | undefined }) {
  const decide = useDistributionStore((s) => s.decidePlan);
  const deciding = useDistributionStore((s) => s.decidingPlan[plan.planId]);
  const [rejecting, setRejecting] = useState(false);
  const [reason, setReason] = useState('');
  const busy = deciding?.status === 'loading';
  const mine = viewer != null && viewer === plan.proposedBy;
  const barred = barredAsOwn(viewer, plan.proposedBy, 'commission');
  const product = plan.productName ?? 'Product not found';
  const title = plan.proposedForAgentId ? `${plan.agentName ?? 'An agent'}'s own rate · ${product}` : product;

  return (
    <div role="listitem" aria-label={title} className="space-y-2 px-4 py-3">
      <div>
        <p className="text-sm font-medium">
          {plan.proposedForAgentId ? (
            <Link to={`/staff/agents/${encodeURIComponent(plan.proposedForAgentId)}`} className="hover:underline">
              {title}
            </Link>
          ) : (
            title
          )}
        </p>
        <p className="text-sm">{describeRules(plan)}</p>
        <p className="text-xs text-muted-foreground">
          Proposed by <StaffName subject={plan.proposedBy ?? null} />
          {plan.proposedAt && <> on {formatInstantDay(plan.proposedAt)}</>}
        </p>
      </div>

      {deciding?.status === 'error' && deciding.error && <InlineError error={deciding.error} />}

      {can('commission.approve') && (
        <>
          {barred && <p className="text-xs text-muted-foreground">You proposed this, so someone else must approve it.</p>}
          {mine && mayActAlone('commission') && <p className="text-xs text-muted-foreground">{SIGNING_OWN}</p>}
          {rejecting ? (
            <div className="space-y-2">
              <FormField required label="Why is it rejected?">
                <Input inputSize="sm" value={reason} onChange={(e) => setReason(e.target.value)} />
              </FormField>
              <div className="flex gap-2">
                <Button size="sm" variant="danger" disabled={reason.trim() === '' || busy}
                  onClick={() => void decide(plan.planId, reason.trim())}>
                  Reject plan
                </Button>
                <Button size="sm" variant="ghost" onClick={() => setRejecting(false)}>
                  Cancel
                </Button>
              </div>
            </div>
          ) : (
            <div className="flex gap-2">
              <Button size="sm" disabled={barred || busy} pending={busy} onClick={() => void decide(plan.planId, null)}>
                Approve plan
              </Button>
              <Button size="sm" variant="ghost" disabled={busy} onClick={() => setRejecting(true)}>
                {mine ? 'Withdraw' : 'Reject'}
              </Button>
            </div>
          )}
        </>
      )}
    </div>
  );
}
