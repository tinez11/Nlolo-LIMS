import { zodResolver } from '@hookform/resolvers/zod';
import { ArrowLeft, Ban, Pause, Play, RotateCcw } from 'lucide-react';
import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import { Link, useParams } from 'react-router-dom';
import { useAuth } from 'react-oidc-context';
import type { LoanView } from '@/api/types';
import { canSeeFinance, readIdentity } from '@/auth/claims';
import { PageHeader } from '@/components/AppShell';
import { DataTable, type Column } from '@/components/DataTable';
import { StatusBadge } from '@/components/StatusBadge';
import { EmptyState, ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { formatDate } from '@/lib/dates';
import { formatMoney } from '@/lib/money';
import { isInitialLoad } from '@/store/createResourceSlice';
import {
  selectCoverage,
  selectDetail,
  selectLoans,
  selectReinstating,
  selectResuming,
  selectSuspending,
  usePolicyStore,
} from '@/store/policyStore';
import { CessionsPanel } from '@/features/reinsurance/CessionsPanel';
import { BeneficiariesPanel } from './BeneficiariesPanel';
import { InvoicesPanel } from './InvoicesPanel';
import { Field } from '@/components/Field';
import {
  blankSuspendPolicyForm,
  suspendPolicyFormSchema,
  toApiRequest as toSuspendApiRequest,
  type SuspendPolicyFormValues,
} from './suspendPolicyForm';

/**
 * The "acts" half of drawer-previews-page-acts: the full record, and where any
 * mutating action would live.
 *
 * The invoice and loan tables use the NO-PAGER variant, because
 * `GET /policies/{n}/invoices` and `.../loans` return bare unpaged arrays -- the
 * whole set arrives in one response and a pager over it would be a lie.
 *
 * The Reinsurance panel is gated on the SAME convenience-decoded role check
 * `AppShell` uses for its own Finance nav group (`GET .../cessions` itself
 * requires FINANCE_OFFICER/ADMIN) -- shown only to a staff user whose own
 * token could actually call it, never a blanket "staff can see everything."
 */
export function PolicyDetailPage() {
  const { policyNumber = '' } = useParams();
  const auth = useAuth();
  const canSeeReinsurance = canSeeFinance(readIdentity(auth.user?.access_token));

  const detail = usePolicyStore(selectDetail(policyNumber));
  const coverage = usePolicyStore(selectCoverage(policyNumber));
  const loans = usePolicyStore(selectLoans(policyNumber));

  const loadDetail = usePolicyStore((s) => s.loadDetail);
  const loadCoverage = usePolicyStore((s) => s.loadCoverage);
  const loadLoans = usePolicyStore((s) => s.loadLoans);

  useEffect(() => {
    if (!policyNumber) return;
    void loadDetail(policyNumber);
    void loadCoverage(policyNumber);
    void loadLoans(policyNumber);
  }, [policyNumber, loadDetail, loadCoverage, loadLoans]);

  const policy = detail.data;

  // isInitialLoad, not a 'loading'-only check: the load fires from an effect that
  // runs AFTER first render, so status is briefly 'idle' -- a 'loading'-only check
  // let that frame fall through toward the error branch below.
  if (isInitialLoad(detail)) {
    return <LoadingBlock label={`Loading ${policyNumber}`} />;
  }

  if (detail.data === null && detail.status === 'error' && detail.error) {
    return (
      <div className="px-6 pt-6">
        <BackLink />
        <ErrorPanel error={detail.error} onRetry={() => void loadDetail(policyNumber)} />
      </div>
    );
  }

  return (
    <>
      <div className="px-6 pt-6">
        <BackLink />
      </div>

      <PageHeader
        title={policyNumber}
        description={
          policy?.productId ? (
            <span className="font-mono text-xs">product {policy.productId}</span>
          ) : undefined
        }
        actions={
          <>
            {policy?.status && <StatusBadge kind="policy" value={policy.status} />}
            {/* POST /policies/{n}/surrender genuinely returns 501 -- the surrender
                choreography was deferred with the workflow engine. Rendered disabled
                rather than as a live button that produces an error. */}
            <Button size="sm" disabled title="Not implemented on the platform yet (HTTP 501)">
              <Ban />
              Surrender
            </Button>
          </>
        }
      />

      <div className="grid gap-5 px-6 pb-8 lg:grid-cols-[minmax(0,1fr)_320px]">
        <div className="space-y-5">
          <Panel title="Invoices" subtitle="All invoices for this policy">
            <InvoicesPanel policyNumber={policyNumber} />
          </Panel>

          <Panel title="Loans" subtitle="Policy loans taken against cash value">
            {renderLoans()}
          </Panel>
        </div>

        <div className="space-y-5">
          <Panel title="Policy">
            {policy && (
              <dl className="px-4 pb-2">
                <Field label="Sum assured" value={formatMoney(policy.sumAssured)} emphasis />
                <Field
                  label="Premium"
                  value={
                    <>
                      {formatMoney(policy.premium)}
                      {policy.premiumFrequency && (
                        <span className="ml-1 text-xs text-subtle-foreground">
                          {policy.premiumFrequency.toLowerCase()}
                        </span>
                      )}
                    </>
                  }
                />
                <Field
                  label="Cash value"
                  value={formatMoney(policy.cashValue)}
                  note="Always 0.00 until the platform credits cash value"
                />
                <Field label="Issued" value={formatDate(policy.issueDate)} />
                <Field
                  label="Policyholder"
                  value={
                    policy.policyholderPartyId ? (
                      <Link to={`/staff/parties/${policy.policyholderPartyId}`} className="font-mono text-xs underline">
                        {policy.policyholderPartyId}
                      </Link>
                    ) : (
                      '—'
                    )
                  }
                />
                <Field
                  label="Agent of record"
                  value={
                    policy.agentOfRecordId ? (
                      <Link to={`/staff/agents/${policy.agentOfRecordId}`} className="font-mono text-xs underline">
                        {policy.agentOfRecordId}
                      </Link>
                    ) : (
                      'Direct — no agent'
                    )
                  }
                />
              </dl>
            )}
          </Panel>

          {policy && <Panel title="Lifecycle">{<LifecycleActions policyNumber={policyNumber} status={policy.status} />}</Panel>}

          <Panel title="Coverage" subtitle="Active benefits as of today">
            {renderCoverage()}
          </Panel>

          <Panel title="Beneficiaries">
            {policy && (
              <BeneficiariesPanel policyNumber={policyNumber} beneficiaries={policy.beneficiaries ?? []} />
            )}
          </Panel>

          {canSeeReinsurance && policy && (
            <Panel title="Reinsurance" subtitle="Cessions this policy's own coverage produced">
              <CessionsPanel policyNumber={policyNumber} />
            </Panel>
          )}
        </div>
      </div>
    </>
  );

  function renderLoans() {
    if (isInitialLoad(loans)) return <LoadingBlock />;
    if (loans.status === 'error' && loans.error && loans.data === null) {
      return <ErrorPanel error={loans.error} onRetry={() => void loadLoans(policyNumber)} />;
    }
    const rows = loans.data ?? [];
    if (rows.length === 0) {
      return (
        <EmptyState
          title="No loans"
          description="No policy loan has been taken against this policy."
        />
      );
    }

    const columns: Column<LoanView>[] = [
      {
        key: 'loanId',
        header: 'Loan',
        render: (l) => <span className="font-mono text-xs">{l.loanId?.slice(0, 8) ?? '—'}</span>,
      },
      { key: 'status', header: 'Status', render: (l) => <StatusBadge kind="loan" value={l.status} /> },
      {
        key: 'rate',
        header: 'Rate',
        align: 'right',
        secondary: true,
        render: (l) =>
          typeof l.currentInterestRate === 'number' ? `${l.currentInterestRate}%` : '—',
      },
      {
        key: 'principal',
        header: 'Principal',
        align: 'right',
        secondary: true,
        render: (l) => formatMoney(l.principalAmount),
      },
      {
        key: 'outstanding',
        header: 'Outstanding',
        align: 'right',
        render: (l) => formatMoney(l.outstandingBalance),
      },
    ];

    return (
      <DataTable
        columns={columns}
        rows={rows}
        rowKey={(l) => l.loanId ?? JSON.stringify(l)}
        caption={`Loans for ${policyNumber}`}
      />
    );
  }

  function renderCoverage() {
    if (isInitialLoad(coverage)) return <LoadingBlock />;
    if (coverage.status === 'error' && coverage.error && coverage.data === null) {
      return <ErrorPanel error={coverage.error} onRetry={() => void loadCoverage(policyNumber)} />;
    }
    const active = coverage.data?.activeCoverages ?? [];
    if (active.length === 0) {
      return (
        <p className="px-4 pb-4 text-xs text-muted-foreground">
          No active coverage as of today.
        </p>
      );
    }
    return (
      <dl className="px-4 pb-2">
        {active.map((c, index) => (
          <Field
            key={`${c.benefitType ?? 'benefit'}-${index}`}
            label={c.benefitType ? c.benefitType.replace(/_/g, ' ').toLowerCase() : 'Benefit'}
            value={formatMoney(c.sumAssured)}
          />
        ))}
      </dl>
    );
  }

}

function BackLink() {
  return (
    <Button asChild variant="ghost" size="sm" className="-ml-2">
      <Link to=".." relative="path">
        <ArrowLeft />
        All policies
      </Link>
    </Button>
  );
}

/**
 * `POST /policies/{n}/suspend`/`resume`/`reinstate` -- staff only, all three
 * fully implemented and tested since M3 but with no HTTP endpoint at all until
 * this staff-portal CRUD audit found the gap. Only one action is ever shown at
 * a time, mirroring the backend's own guards exactly: ACTIVE -> Suspend,
 * SUSPENDED -> Resume, LAPSED -> Reinstate. A REINSTATED policy shows none of
 * the three -- `Policy.suspend()` requires status == ACTIVE, and a reinstated
 * policy stays labeled REINSTATED rather than being written back to ACTIVE
 * (Policy.java's own comment), so it is genuinely not eligible for a further
 * suspend through this same action despite being in force.
 */
function LifecycleActions({
  policyNumber,
  status,
}: {
  policyNumber: string;
  status: string | undefined;
}) {
  const [suspendFormOpen, setSuspendFormOpen] = useState(false);

  if (status === 'ACTIVE') {
    return suspendFormOpen ? (
      <SuspendForm policyNumber={policyNumber} onDone={() => setSuspendFormOpen(false)} />
    ) : (
      <div className="px-4 pb-4">
        <Button size="sm" onClick={() => setSuspendFormOpen(true)}>
          <Pause />
          Suspend
        </Button>
      </div>
    );
  }

  if (status === 'SUSPENDED') {
    return <ResumeAction policyNumber={policyNumber} />;
  }

  if (status === 'LAPSED') {
    return <ReinstateAction policyNumber={policyNumber} />;
  }

  return <p className="px-4 pb-4 text-xs text-muted-foreground">No lifecycle action available for {status ?? 'this status'}.</p>;
}

function SuspendForm({ policyNumber, onDone }: { policyNumber: string; onDone: () => void }) {
  const suspendPolicy = usePolicyStore((s) => s.suspendPolicy);
  const resetSuspendPolicy = usePolicyStore((s) => s.resetSuspendPolicy);
  const suspending = usePolicyStore(selectSuspending(policyNumber));

  useEffect(() => {
    resetSuspendPolicy(policyNumber);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [policyNumber]);

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<SuspendPolicyFormValues>({
    resolver: zodResolver(suspendPolicyFormSchema),
    defaultValues: blankSuspendPolicyForm(),
  });

  async function onSubmit(values: SuspendPolicyFormValues) {
    await suspendPolicy(policyNumber, toSuspendApiRequest(values));
    if (usePolicyStore.getState().suspending[policyNumber]?.status === 'success') onDone();
  }

  return (
    <form
      className="mx-4 mb-4 space-y-2 rounded-md border border-border p-2.5"
      onSubmit={(e) => void handleSubmit(onSubmit)(e)}
    >
      <label className="block">
        <span className="mb-1 block text-[11px] font-medium text-muted-foreground">Reason</span>
        <input
          className="h-8 w-full rounded-md border border-input bg-surface px-2 text-xs"
          placeholder="Employer group scheme in arrears"
          {...register('reason')}
        />
        {errors.reason?.message && (
          <p className="mt-1 text-[11px] text-status-danger-fg">{errors.reason.message}</p>
        )}
      </label>

      {suspending.status === 'error' && suspending.error && (
        <p role="alert" className="text-[11px] text-status-danger-fg">
          {suspending.error.detail ?? suspending.error.title}
        </p>
      )}

      <div className="flex items-center gap-1.5">
        <Button type="submit" size="sm" variant="primary" disabled={suspending.status === 'loading'}>
          {suspending.status === 'loading' ? 'Suspending…' : 'Suspend policy'}
        </Button>
        <Button type="button" size="sm" variant="ghost" onClick={onDone}>
          Cancel
        </Button>
      </div>
    </form>
  );
}

function ResumeAction({ policyNumber }: { policyNumber: string }) {
  const resumePolicy = usePolicyStore((s) => s.resumePolicy);
  const resetResumePolicy = usePolicyStore((s) => s.resetResumePolicy);
  const resuming = usePolicyStore(selectResuming(policyNumber));

  useEffect(() => {
    resetResumePolicy(policyNumber);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [policyNumber]);

  return (
    <div className="space-y-2 px-4 pb-4">
      {resuming.status === 'error' && resuming.error && (
        <p role="alert" className="text-[11px] text-status-danger-fg">
          {resuming.error.detail ?? resuming.error.title}
        </p>
      )}
      <Button size="sm" disabled={resuming.status === 'loading'} onClick={() => void resumePolicy(policyNumber)}>
        <Play />
        {resuming.status === 'loading' ? 'Resuming…' : 'Resume'}
      </Button>
    </div>
  );
}

function ReinstateAction({ policyNumber }: { policyNumber: string }) {
  const reinstatePolicy = usePolicyStore((s) => s.reinstatePolicy);
  const resetReinstatePolicy = usePolicyStore((s) => s.resetReinstatePolicy);
  const reinstating = usePolicyStore(selectReinstating(policyNumber));

  useEffect(() => {
    resetReinstatePolicy(policyNumber);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [policyNumber]);

  return (
    <div className="space-y-2 px-4 pb-4">
      {reinstating.status === 'error' && reinstating.error && (
        <p role="alert" className="text-[11px] text-status-danger-fg">
          {reinstating.error.detail ?? reinstating.error.title}
        </p>
      )}
      <Button size="sm" disabled={reinstating.status === 'loading'} onClick={() => void reinstatePolicy(policyNumber)}>
        <RotateCcw />
        {reinstating.status === 'loading' ? 'Reinstating…' : 'Reinstate'}
      </Button>
    </div>
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
