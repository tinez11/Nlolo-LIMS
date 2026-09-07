import { useEffect } from 'react';
import { PageHeader } from '@/components/PageHeader';
import { Field } from '@/components/Field';
import { StatusBadge } from '@/components/StatusBadge';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { formatDate } from '@/lib/dates';
import { isInitialLoad } from '@/store/createResourceSlice';
import { selectOwnAgent, useDistributionStore } from '@/store/distributionStore';
import { useProductStore } from '@/store/productStore';
import { CommissionPlanPanel } from './CommissionPlanPanel';
import { CommissionStatementsPanel } from './CommissionStatementsPanel';
import { Panel } from '@/components/Panel';
import { DetailLayout } from '@/components/DetailLayout';

/**
 * The agent realm's landing page: `GET /agents/me` resolves the caller's own
 * agentId (there is no other way to discover it -- see that endpoint's own
 * javadoc), then reuses the exact same `CommissionPlanPanel`/
 * `CommissionStatementsPanel` the staff console's `AgentDetailPage` uses,
 * with `canManage` hardcoded false: creating a plan and requesting a payout
 * are staff FINANCE_OFFICER/ADMIN actions, never something an agent does to
 * their own record, matching the backend's own `@PreAuthorize` gates exactly.
 */
export function AgentProfilePage() {
  const own = useDistributionStore(selectOwnAgent);
  const loadOwnAgent = useDistributionStore((s) => s.loadOwnAgent);
  const products = useProductStore((s) => s.list);
  const loadProducts = useProductStore((s) => s.loadList);

  useEffect(() => {
    void loadOwnAgent();
  }, [loadOwnAgent]);

  useEffect(() => {
    void loadProducts();
  }, [loadProducts]);

  if (isInitialLoad(own)) {
    return <LoadingBlock label="Loading your profile" />;
  }

  if (own.data === null && own.status === 'error' && own.error) {
    return (
      <div className="px-6 pt-6">
        <ErrorPanel error={own.error} onRetry={() => void loadOwnAgent()} />
      </div>
    );
  }

  const agent = own.data;
  if (!agent) return null;

  return (
    <>
      <PageHeader
        title="My profile"
        description={<span className="font-mono text-xs">{agent.licenseNumber}</span>}
        actions={agent.licenseStatus && <StatusBadge kind="agentLicense" value={agent.licenseStatus} />}
      />

      <DetailLayout
        record={
          <Panel title="License">
            <dl className="px-4 pb-2">
              <Field label="Agent id" value={<span className="font-mono text-xs">{agent.agentId}</span>} />
              <Field label="License expiry" value={formatDate(agent.licenseExpiryDate)} />
              {agent.hierarchyParentId ? (
                <Field
                  label="Reports to"
                  value={<span className="font-mono text-xs">{agent.hierarchyParentId}</span>}
                  note="No drill-in yet -- your supervisor's own profile is not reachable from here"
                />
              ) : (
                <Field label="Reports to" value="Top of hierarchy" />
              )}
              <Field
                label="Own plan override"
                value={agent.commissionPlanId ?? '—'}
                note="Null means the product's active plan applies instead"
              />
            </dl>
          </Panel>
        }
      >
        <Panel title="Commission plan" subtitle="Per product -- a plan hangs off a product, not you.">
          <CommissionPlanPanel agentId={agent.agentId ?? ''} products={products.data ?? []} canManage={false} />
        </Panel>

        <Panel title="Commission statements">
          <CommissionStatementsPanel agentId={agent.agentId ?? ''} canManage={false} />
        </Panel>
      </DetailLayout>
    </>
  );
}

