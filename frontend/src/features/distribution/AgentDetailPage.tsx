import { ArrowLeft } from 'lucide-react';
import { useEffect } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useAuth } from 'react-oidc-context';
import { readIdentity, staffRoles } from '@/auth/claims';
import { PageHeader } from '@/components/AppShell';
import { Field } from '@/components/Field';
import { StatusBadge } from '@/components/StatusBadge';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { formatDate } from '@/lib/dates';
import { isInitialLoad } from '@/store/createResourceSlice';
import { selectAgent, useDistributionStore } from '@/store/distributionStore';
import { useProductStore } from '@/store/productStore';
import { CommissionPlanPanel } from './CommissionPlanPanel';
import { CommissionStatementsPanel } from './CommissionStatementsPanel';

/**
 * Reached from `OnboardAgentPage`'s own redirect, or by drilling in from a
 * policy's `agentOfRecordId` (unlike underwriting's case id, this one DOES
 * round-trip through `GET /policies` -- confirmed on the real wire DTO).
 * There is still no `GET /agents` list anywhere, so this page is the entire
 * read surface for a single agent: profile, applicable commission plan (per
 * product), statements, and each statement's accrual line items.
 */
export function AgentDetailPage() {
  const { agentId = '' } = useParams();
  const auth = useAuth();
  const roles = staffRoles(readIdentity(auth.user?.access_token));
  const canManage = roles.FINANCE_OFFICER || roles.ADMIN;

  const detail = useDistributionStore(selectAgent(agentId));
  const loadAgent = useDistributionStore((s) => s.loadAgent);

  const products = useProductStore((s) => s.list);
  const loadProducts = useProductStore((s) => s.loadList);

  useEffect(() => {
    if (agentId) void loadAgent(agentId);
  }, [agentId, loadAgent]);

  useEffect(() => {
    void loadProducts();
  }, [loadProducts]);

  const agent = detail.data;

  if (isInitialLoad(detail)) {
    return <LoadingBlock label="Loading agent" />;
  }

  if (detail.data === null && detail.status === 'error' && detail.error) {
    return (
      <div className="px-6 pt-6">
        <BackLink />
        <ErrorPanel error={detail.error} onRetry={() => void loadAgent(agentId)} />
      </div>
    );
  }

  return (
    <>
      <div className="px-6 pt-6">
        <BackLink />
      </div>

      <PageHeader
        title={agent?.licenseNumber ?? 'Agent'}
        description={<span className="font-mono text-xs">{agentId}</span>}
        actions={agent?.licenseStatus && <StatusBadge kind="agentLicense" value={agent.licenseStatus} />}
      />

      <div className="grid gap-5 px-6 pb-8 lg:grid-cols-[minmax(0,1fr)_320px]">
        <div className="space-y-5">
          <Panel title="Commission plan" subtitle="Per product -- a plan hangs off a product, not this agent.">
            <CommissionPlanPanel agentId={agentId} products={products.data ?? []} canManage={canManage} />
          </Panel>

          <Panel title="Commission statements">
            <CommissionStatementsPanel agentId={agentId} canManage={canManage} />
          </Panel>
        </div>

        <div className="space-y-5">
          <Panel title="Agent">
            {agent && (
              <dl className="px-4 pb-2">
                <Field
                  label="Party"
                  value={<span className="font-mono text-xs">{agent.partyId ?? '—'}</span>}
                  note="No party lookup endpoint exists yet"
                />
                <Field label="License expiry" value={formatDate(agent.licenseExpiryDate)} />
                <Field
                  label="Hierarchy parent"
                  value={
                    agent.hierarchyParentId ? (
                      <Link
                        to={`../${agent.hierarchyParentId}`}
                        relative="path"
                        className="font-mono text-xs underline"
                      >
                        {agent.hierarchyParentId}
                      </Link>
                    ) : (
                      'Top of hierarchy'
                    )
                  }
                />
                <Field
                  label="Own plan override"
                  value={agent.commissionPlanId ?? '—'}
                  note="Null means the product's active plan applies instead"
                />
              </dl>
            )}
          </Panel>
        </div>
      </div>
    </>
  );
}

function BackLink() {
  return (
    <Button asChild variant="ghost" size="sm" className="-ml-2">
      <Link to="../new" relative="path">
        <ArrowLeft />
        Onboard another agent
      </Link>
    </Button>
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
