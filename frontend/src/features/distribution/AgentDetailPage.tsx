import { Pause, Play } from 'lucide-react';
import { useEffect } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useAuth } from 'react-oidc-context';
import { readIdentity, staffRoles } from '@/auth/claims';
import { PageHeader } from '@/components/PageHeader';
import { InlineError } from '@/components/InlineError';
import { Field } from '@/components/Field';
import { AgentName } from '@/components/AgentName';
import { PartyName } from '@/components/PartyName';
import { StatusBadge } from '@/components/StatusBadge';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { formatDate } from '@/lib/dates';
import { isInitialLoad } from '@/store/createResourceSlice';
import {
  selectAgent,
  selectReactivatingAgent,
  selectSuspendingAgent,
  useDistributionStore,
} from '@/store/distributionStore';
import { useProductStore } from '@/store/productStore';
import { CommissionPlanPanel } from './CommissionPlanPanel';
import { CommissionStatementsPanel } from './CommissionStatementsPanel';
import { Panel } from '@/components/Panel';
import { DetailLayout } from '@/components/DetailLayout';

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
      <>
        {/* The bar renders on the error path too, so a record that fails to load keeps its
            heading and its way out instead of leaving a bare panel. */}
        <PageHeader breadcrumb={[{ label: 'Agents', to: '/staff/agents' }]} title="Agent" />
        <div className="px-6 pt-6">
          <ErrorPanel error={detail.error} onRetry={() => void loadAgent(agentId)} />
        </div>
      </>
    );
  }

  return (
    <>
      {/*
        The licence number stays the title -- an agent IS a licence here, the register is
        keyed by it and `GET /agents?q=` matches on it. What the byline said was the agent's
        own uuid and nothing else, so arriving from a policy's "Agent of record" told you
        which licence you were looking at but never WHO. The name goes beside the id, which
        stays because this is the record that owns it: an id on its own page is identity,
        not an unresolved reference. `PartyName` here, not `AgentName` -- the profile is
        already loaded, so its partyId costs no second request.
      */}
      <PageHeader
        breadcrumb={[{ label: 'Agents', to: '/staff/agents' }]}
        title={agent?.licenseNumber ?? 'Agent'}
        description={
          <span className="flex flex-wrap items-baseline gap-x-2">
            {agent?.partyId && <PartyName partyId={agent.partyId} />}
            <span className="font-mono text-xs select-all">{agentId}</span>
          </span>
        }
        actions={agent?.licenseStatus && <StatusBadge kind="agentLicense" value={agent.licenseStatus} />}
      />

      <DetailLayout record={renderRecord()}>
        <Panel title="Commission plan" subtitle="Per product -- a plan hangs off a product, not this agent.">
          <CommissionPlanPanel agentId={agentId} products={products.data ?? []} canManage={canManage} />
        </Panel>

        <Panel title="Commission statements">
          <CommissionStatementsPanel agentId={agentId} canManage={canManage} />
        </Panel>

        {/* Last, and without `emphasis`. Suspending a licence is not what this page
            is opened to do -- a finance officer came for the commission statements --
            but it is not a marginal note in the rail either. */}
        {canManage && agent && (
          <Panel title="Lifecycle">
            <LifecycleActions agentId={agentId} status={agent.licenseStatus} />
          </Panel>
        )}
      </DetailLayout>
    </>
  );

  /* Declared after the return so the rail's JSX stays where it was rather than being
     hoisted above the column it belongs beside -- function declarations hoist, the
     same trick `renderCoverage` uses on the policy page. */
  function renderRecord() {
    return (
      <>
        <Panel title="Agent">
          {agent && (
            <dl className="px-4 pb-2">
              <Field
                label="Party"
                value={
                  agent.partyId ? (
                    <Link to={`/staff/parties/${agent.partyId}`} className="underline">
                      <PartyName partyId={agent.partyId} />
                    </Link>
                  ) : (
                    '—'
                  )
                }
              />
              <Field label="License expiry" value={formatDate(agent.licenseExpiryDate)} />
              <Field
                label="Hierarchy parent"
                value={
                  agent.hierarchyParentId ? (
                    // A reference to ANOTHER agent, so it resolves like one. The id above
                    // is this record's own; this one belonged to somebody else and read as
                    // a second uuid with no way to tell whose upline it was.
                    <Link to={`../${agent.hierarchyParentId}`} relative="path" className="underline">
                      <AgentName agentId={agent.hierarchyParentId} />
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
      </>
    );
  }
}


/**
 * `POST /agents/{n}/suspend`/`reactivate` -- `AgentProfile.setLicenseStatus`
 * has existed since M7 with no caller anywhere on the platform until this
 * staff-portal CRUD audit found the gap. Only one action is ever shown,
 * mirroring the backend's own guards: ACTIVE -> Suspend, SUSPENDED ->
 * Reactivate. An EXPIRED agent shows neither -- reactivating past an expiry
 * is not something this endpoint does (expiry is calendar-driven, not a
 * staff decision to undo).
 */
function LifecycleActions({
  agentId,
  status,
}: {
  agentId: string;
  status: string | undefined;
}) {
  if (status === 'ACTIVE') {
    return <SuspendAction agentId={agentId} />;
  }
  if (status === 'SUSPENDED') {
    return <ReactivateAction agentId={agentId} />;
  }
  return (
    <p className="px-4 pb-4 text-xs text-muted-foreground">
      No lifecycle action available for {status ?? 'this status'}.
    </p>
  );
}

function SuspendAction({ agentId }: { agentId: string }) {
  const suspendAgent = useDistributionStore((s) => s.suspendAgent);
  const resetSuspendAgent = useDistributionStore((s) => s.resetSuspendAgent);
  const suspending = useDistributionStore(selectSuspendingAgent(agentId));

  useEffect(() => {
    resetSuspendAgent(agentId);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [agentId]);

  return (
    <div className="space-y-2 px-4 pb-4">
      {suspending.status === 'error' && suspending.error && (
        <InlineError error={suspending.error} />
      )}
      <Button size="sm" pending={suspending.status === 'loading'} onClick={() => void suspendAgent(agentId)}>
        <Pause />
        Suspend
      </Button>
    </div>
  );
}

function ReactivateAction({ agentId }: { agentId: string }) {
  const reactivateAgent = useDistributionStore((s) => s.reactivateAgent);
  const resetReactivateAgent = useDistributionStore((s) => s.resetReactivateAgent);
  const reactivating = useDistributionStore(selectReactivatingAgent(agentId));

  useEffect(() => {
    resetReactivateAgent(agentId);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [agentId]);

  return (
    <div className="space-y-2 px-4 pb-4">
      {reactivating.status === 'error' && reactivating.error && (
        <InlineError error={reactivating.error} />
      )}
      <Button size="sm" pending={reactivating.status === 'loading'} onClick={() => void reactivateAgent(agentId)}>
        <Play />
        Reactivate
      </Button>
    </div>
  );
}

