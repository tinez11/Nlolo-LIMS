import { useEffect } from 'react';
import { PageHeader } from '@/components/AppShell';
import { Field } from '@/components/Field';
import { StatusBadge } from '@/components/StatusBadge';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { formatDate } from '@/lib/dates';
import { isInitialLoad } from '@/store/createResourceSlice';
import { selectOwnAgent, useDistributionStore } from '@/store/distributionStore';
import { useProductStore } from '@/store/productStore';
import { CommissionPlanPanel } from './CommissionPlanPanel';
import { CommissionStatementsPanel } from './CommissionStatementsPanel';

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

      <div className="grid gap-5 px-6 pb-8 lg:grid-cols-[minmax(0,1fr)_320px]">
        <div className="space-y-5">
          <Panel title="Commission plan" subtitle="Per product -- a plan hangs off a product, not you.">
            <CommissionPlanPanel agentId={agent.agentId ?? ''} products={products.data ?? []} canManage={false} />
          </Panel>

          <Panel title="Commission statements">
            <CommissionStatementsPanel agentId={agent.agentId ?? ''} canManage={false} />
          </Panel>
        </div>

        <div className="space-y-5">
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
        </div>
      </div>
    </>
  );
}

function Panel({
  title,
  subtitle,
  children,
}: {
  title: string;
  subtitle?: string;
  children: React.ReactNode;
}) {
  return (
    <section className="rounded-lg border border-border bg-surface">
      <div className="border-b border-border px-4 py-3">
        <h2 className="text-sm font-semibold">{title}</h2>
        {subtitle && <p className="text-xs text-muted-foreground">{subtitle}</p>}
      </div>
      {children}
    </section>
  );
}
