import { useEffect, useState } from 'react';
import { getOwnSupervisor, type AgentSupervisor } from '@/api/distribution';
import { PageHeader } from '@/components/PageHeader';
import { Field } from '@/components/Field';
import { StatusBadge } from '@/components/StatusBadge';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { formatDate } from '@/lib/dates';
import { isInitialLoad } from '@/store/createResourceSlice';
import { selectOwnAgent, useDistributionStore } from '@/store/distributionStore';
import { useProductStore } from '@/store/productStore';
import { Link } from 'react-router-dom';
import { CommissionPlanPanel } from './CommissionPlanPanel';
import { Panel } from '@/components/Panel';
import { DetailLayout } from '@/components/DetailLayout';

/**
 * The agent realm's landing page: `GET /agents/me` resolves the caller's own
 * agentId (there is no other way to discover it -- see that endpoint's own
 * javadoc), then reuses the exact same `CommissionPlanPanel` the staff
 * console's `AgentDetailPage` uses, with `canManage` hardcoded false: creating
 * a plan is a staff FINANCE_OFFICER/ADMIN action, never something an agent does
 * to their own record, matching the backend's own `@PreAuthorize` gates exactly.
 *
 * <p>What the agent has EARNED is no longer duplicated here: it is a screen of its
 * own ("My commission"), which this page links to (spec 2026-10-12).
 */
/**
 * Who the agent reports to, by name and how to reach them (2026-11-08, the user: the supervisor showed as a raw id
 * with "No drill-in yet"). An agent cannot open its supervisor's record -- that would read up the hierarchy -- so
 * `GET /agents/me/supervisor` gives contact details only.
 */
export function ReportsTo({ supervisorId }: { supervisorId: string }) {
  const [result, setResult] = useState<{ for: string; supervisor: AgentSupervisor | null; failed: boolean } | null>(null);

  useEffect(() => {
    let cancelled = false;
    getOwnSupervisor()
      .then((supervisor) => !cancelled && setResult({ for: supervisorId, supervisor, failed: false }))
      .catch(() => !cancelled && setResult({ for: supervisorId, supervisor: null, failed: true }));
    return () => {
      cancelled = true;
    };
  }, [supervisorId]);

  const current = result?.for === supervisorId ? result : null;
  if (!current) return <Field label="Reports to" value="Loading…" />;
  const s = current.supervisor;
  if (!s) {
    return current.failed ? (
      <Field label="Reports to" value="Your supervisor" note="Their name could not be loaded just now" />
    ) : (
      <Field label="Reports to" value="Your supervisor" />
    );
  }
  return (
    <Field
      label="Reports to"
      value={
        <span>
          {s.displayName ?? 'Your supervisor'} <span className="font-mono text-xs text-muted-foreground">{s.licenseNumber}</span>
          {s.phoneNumber && (
            <>
              <br />
              <a href={`tel:${s.phoneNumber}`} className="underline">{s.phoneNumber}</a>
            </>
          )}
        </span>
      }
    />
  );
}

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
                <ReportsTo supervisorId={agent.hierarchyParentId} />
              ) : (
                <Field label="Reports to" value="No one — you are at the top" />
              )}
              <Field
                label="Commission plan"
                value={agent.commissionPlanId ? 'Your own plan, shown below' : 'The product’s plan, shown below'}
              />
            </dl>
          </Panel>
        }
      >
        <Panel title="Commission plan">
          <CommissionPlanPanel agentId={agent.agentId ?? ''} products={products.data ?? []} canManage={false} />
        </Panel>

        <Panel title="Commission earned">
          <p className="px-4 pb-4 text-sm">
            <Link to="/agents/commission" className="underline">
              View my commission
            </Link>
          </p>
        </Panel>
      </DetailLayout>
    </>
  );
}

