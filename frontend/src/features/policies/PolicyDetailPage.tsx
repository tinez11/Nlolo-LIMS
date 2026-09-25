import { zodResolver } from '@hookform/resolvers/zod';
import { ArrowLeft, Ban, Pause, Play, RotateCcw, Users } from 'lucide-react';
import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import { Link, useParams } from 'react-router-dom';
import { useAuth } from 'react-oidc-context';
import { canSeeFinance, readIdentity } from '@/auth/claims';
import type { Realm } from '@/auth/realms';
import { PageHeader } from '@/components/PageHeader';
import { AgentName } from '@/components/AgentName';
import { PartyName } from '@/components/PartyName';
import { ProductName } from '@/components/ProductName';
import { StatusBadge } from '@/components/StatusBadge';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { formatDate, formatMonths } from '@/lib/dates';
import { formatMoney } from '@/lib/money';
import { isInitialLoad } from '@/store/createResourceSlice';
import {
  selectCoverage,
  selectDetail,
  selectInvoices,
  selectReinstating,
  selectResuming,
  selectSuspending,
  usePolicyStore,
} from '@/store/policyStore';
import { MessagesPanel } from '@/features/communications/MessagesPanel';
import { CessionsPanel } from '@/features/reinsurance/CessionsPanel';
import { BeneficiariesPanel } from './BeneficiariesPanel';
import { InvoicesPanel } from './InvoicesPanel';
import { LoansPanel } from './LoansPanel';
import { ConfirmAct } from '@/components/ConfirmAct';
import { Field } from '@/components/Field';
import { FormField } from '@/components/FormField';
import {
  blankSuspendPolicyForm,
  suspendPolicyFormSchema,
  toApiRequest as toSuspendApiRequest,
  type SuspendPolicyFormValues,
} from './suspendPolicyForm';
import { Panel } from '@/components/Panel';
import { DetailLayout } from '@/components/DetailLayout';
import { Input } from '@/components/ui/input';

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
export function PolicyDetailPage({ realm = 'staff' }: { realm?: Realm } = {}) {
  const { policyNumber = '' } = useParams();
  const auth = useAuth();
  const canSeeReinsurance = canSeeFinance(readIdentity(auth.user?.access_token));
  const isStaff = realm === 'staff';

  const detail = usePolicyStore(selectDetail(policyNumber));
  const coverage = usePolicyStore(selectCoverage(policyNumber));

  const loadDetail = usePolicyStore((s) => s.loadDetail);
  const loadCoverage = usePolicyStore((s) => s.loadCoverage);

  useEffect(() => {
    if (!policyNumber) return;
    void loadDetail(policyNumber);
    void loadCoverage(policyNumber);
  }, [policyNumber, loadDetail, loadCoverage]);

  const policy = detail.data;
  // Loaded by the Invoices panel on this same page; read here only to count them.
  const invoiceCount = usePolicyStore(selectInvoices(policyNumber)).data?.length ?? 0;

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
          policy?.productId ? <ProductName productId={policy.productId} /> : undefined
        }
        actions={
          <>
            {policy?.status && <StatusBadge kind="policy" value={policy.status} />}
            {/* Only on a scheme, and only in the staff console -- the members
                endpoint is staff-only, so an agent following this link would get a
                403 rather than a page. A group policy read here answers "one
                contract, 500 lives, sum assured X", which is true and useless to
                somebody administering the schedule; this is the way across. */}
            {isStaff && policy?.productCategory === 'GROUP_LIFE' && (
              <Button asChild size="sm">
                <Link to={`/staff/group-schemes/${encodeURIComponent(policyNumber)}`}>
                  <Users />
                  Member schedule
                </Link>
              </Button>
            )}
            {/* A credit-life scheme goes to its OWN page rather than the member schedule. The
                schedule answers "who is on this scheme"; a lender's book is administered one
                monthly file at a time, and "what happened to this book this month" is the
                question somebody arriving here actually has. The roll is reachable from there. */}
            {isStaff && policy?.productCategory === 'CREDIT_LIFE' && (
              <Button asChild size="sm">
                <Link to={`/staff/credit-life-schemes/${encodeURIComponent(policyNumber)}`}>
                  <Users />
                  Monthly files
                </Link>
              </Button>
            )}
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

      {/*
        Rebalanced rather than reordered. The rail was carrying five panels -- the
        record, the lifecycle actions, coverage, beneficiaries and reinsurance --
        against two in the wide column, so three quarters of this page lived in 320px
        while two tables had 1fr to themselves.

        Nothing takes `emphasis` here, deliberately. Unlike a claim or an underwriting
        case, this page is not opened to perform one act: most visits are somebody
        looking up an invoice. Lifecycle still leaves the rail -- suspending a policy
        is not a marginal note -- but it sits after the record it acts on rather than
        being promoted above it.

        ORDER: the bounded panels come before the unbounded ones. Beneficiaries is a
        handful of rows and a term of the contract; Invoices and Loans are ledgers
        that grow for the life of the policy, and this one is already twenty rows
        deep. Leading with a ledger buries everything after it -- put concretely, it
        put the beneficiary editor and its party picker below the fold of a page that
        keeps getting longer, which broke reaching them at all rather than merely
        making it tedious.
      */}
      <DetailLayout record={renderRecord()}>
        {/*
          First, above everything else, and not a badge.

          "Is this person covered?" is the single most important thing this page answers, and
          since cover began waiting for the first premium the status alone no longer answers it
          for a reader who does not already know the rule. PROPOSED looks like a normal status;
          nothing about the word tells you the customer is uninsured, or what would change that.
        */}
        {policy?.status === 'PROPOSED' && (
          <Panel title="Not yet on cover">
            <p className="px-4 pb-4 text-xs text-fg-muted">
              This is an offer, not a policy in force. Cover starts when the first premium
              clears — until then no claim can be settled against it. The invoices below are
              what the customer pays to accept.
            </p>
          </Panel>
        )}
        {policy?.status === 'NOT_TAKEN_UP' && (
          <Panel title="Offer expired unpaid">
            <p className="px-4 pb-4 text-xs text-fg-muted">
              This offer was never taken up: no first premium arrived within the offer window, so
              it closed. Cover never started, which is why this is not a lapse — it does not
              count against persistency. A new application is needed to insure this person.
            </p>
          </Panel>
        )}

        <Panel title="Beneficiaries">
          {policy && (
            <BeneficiariesPanel policyNumber={policyNumber} beneficiaries={policy.beneficiaries ?? []} />
          )}
        </Panel>

        {/* Directly under the cover panels, because it answers their follow-up question. An
            offer that is "not yet on cover" immediately raises "does the customer know?", and
            the honest answer is a SENT row with a timestamp or a FAILED row with a reason. */}
        <Panel title="Messages" subtitle="What this customer has been told about this policy">
          <MessagesPanel policyNumber={policyNumber} />
        </Panel>

        <Panel title="Invoices" subtitle="All invoices for this policy">
          <InvoicesPanel policyNumber={policyNumber} />
        </Panel>

        <Panel title="Loans" subtitle="Policy loans taken against cash value">
          <LoansPanel policyNumber={policyNumber} cashValue={policy?.cashValue} />
        </Panel>

        {canSeeReinsurance && policy && (
          <Panel title="Reinsurance" subtitle="Cessions this policy's own coverage produced">
            <CessionsPanel policyNumber={policyNumber} />
          </Panel>
        )}

        {/* Suspend/resume/reinstate are all hasRole('REALM_STAFF') only -- shown only
            in the staff console, not just left to always-403 on click, the same
            "don't render a button that can never work for this session" discipline
            the deferred surrender action already follows above. */}
        {isStaff && policy && (
          <Panel title="Lifecycle">
            <LifecycleActions policyNumber={policyNumber} status={policy.status} />
          </Panel>
        )}
      </DetailLayout>
    </>
  );

  function renderRecord() {
    return (
      <>
        <Panel title="Policy">
          {policy && (
            <dl className="px-4 pb-2">
              <Field
                label="Sum assured"
                value={formatMoney(policy.sumAssured)}
                emphasis
                // On a scheme this figure is not a term of the contract anyone
                // typed -- it is the total of the member schedule, restated
                // whenever somebody joins or leaves. Saying so stops it being
                // read as a fixed sum that has quietly changed.
                {...(policy.productCategory === 'GROUP_LIFE' || policy.productCategory === 'CREDIT_LIFE'
                  ? {
                      note: 'The total of every covered member — it moves as the schedule does, and keeps its last figure once nobody is covered.',
                    }
                  : {})}
              />
              {policy.productCategory === 'CREDIT_LIFE' ? (
                /*
                  NOT policy.premium. On credit life that is a figure typed at set-up; the real
                  premium is charged file by file, per borrower, at the scheme's rate. Showing
                  "500,000.00 single" beside a 13,800 invoice was a number that reconciled with
                  nothing.
                */
                // No total here: totals are the backend's to compute (lib/money), and billing
                // publishes none across files. Each invoice below states its own.
                <Field
                  label="Premium"
                  value="Charged per monthly file"
                  note={`Single premium per borrower at the scheme's rate, invoiced when a file is accepted${
                    invoiceCount ? ` — ${invoiceCount} invoice${invoiceCount === 1 ? '' : 's'} so far, see Invoices` : ''
                  }.`}
                />
              ) : (
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
              )}
              <Field
                label="Cash value"
                value={formatMoney(policy.cashValue)}
                note="Always 0.00 until the platform credits cash value"
              />
              <Field label="Issued" value={formatDate(policy.issueDate)} />
              <Field
                label="Risk commences"
                value={formatDate(policy.commencementDate)}
                // Not the same date as "Issued", and the difference is the point: a
                // policy issued today may carry risk from next month.
                {...(policy.commencementDate
                  ? {}
                  : { note: 'Not recorded — issued before the term was captured.' })}
              />
              <Field
                label="Term"
                value={policy.policyTermMonths ? formatMonths(policy.policyTermMonths) : '—'}
                {...(policy.premiumPayingTermMonths &&
                policy.premiumPayingTermMonths !== policy.policyTermMonths
                  ? { note: `Premiums paid for ${formatMonths(policy.premiumPayingTermMonths)}.` }
                  : {})}
              />
              <Field
                label="Matures"
                value={formatDate(policy.maturityDate)}
                {...(policy.maturityDate
                  ? {}
                  : { note: 'This product does not mature, or no term is on record.' })}
              />
              {/* Rendered only when the two differ. On a self-insured policy — the
                  common case — a second row repeating the same name would be noise
                  that teaches people to skip the panel. */}
              {policy.lifeAssuredPartyId &&
                policy.lifeAssuredPartyId !== policy.policyholderPartyId && (
                  <Field
                    label="Life assured"
                    value={
                      isStaff ? (
                        <Link
                          to={`/staff/parties/${policy.lifeAssuredPartyId}`}
                          className="underline"
                        >
                          <PartyName partyId={policy.lifeAssuredPartyId} />
                        </Link>
                      ) : (
                        <PartyName partyId={policy.lifeAssuredPartyId} />
                      )
                    }
                    note="A death claim is assessed against this person, not the policyholder."
                  />
                )}
              <Field
                label="Policyholder"
                value={
                  policy.policyholderPartyId ? (
                    isStaff ? (
                      <Link to={`/staff/parties/${policy.policyholderPartyId}`} className="underline">
                        <PartyName partyId={policy.policyholderPartyId} />
                      </Link>
                    ) : (
                      <PartyName partyId={policy.policyholderPartyId} />
                    )
                  ) : (
                    '—'
                  )
                }
                {...(!isStaff && policy.policyholderPartyId
                  ? { note: 'No drill-in yet outside the staff console' }
                  : {})}
              />
              <Field
                label="Agent of record"
                value={
                  policy.agentOfRecordId ? (
                    isStaff ? (
                      <Link to={`/staff/agents/${policy.agentOfRecordId}`} className="underline">
                        <AgentName agentId={policy.agentOfRecordId} />
                      </Link>
                    ) : (
                      <AgentName agentId={policy.agentOfRecordId} />
                    )
                  ) : (
                    'Direct — no agent'
                  )
                }
              />
            </dl>
          )}
        </Panel>

        <Panel title="Coverage" subtitle="Active benefits as of today">
          {renderCoverage()}
        </Panel>
      </>
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
      <FormField label="Reason" error={errors.reason?.message}>
        <Input
          inputSize="sm"
          placeholder="Employer group scheme in arrears"
          {...register('reason')}
        />
      </FormField>

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

  // No form here, so the arming click is the button itself.
  const [armed, setArmed] = useState(false);

  return (
    <div className="space-y-2 px-4 pb-4">
      {reinstating.status === 'error' && reinstating.error && (
        <p role="alert" className="text-[11px] text-status-danger-fg">
          {reinstating.error.detail ?? reinstating.error.title}
        </p>
      )}
      {armed ? (
        <ConfirmAct
          heading="Reinstate this policy?"
          consequence={
            <>
              Puts <strong>{policyNumber}</strong> back in force and restarts billing on it.
            </>
          }
          /*
            Policy.reinstate() moves LAPSED -> REINSTATED, and there is no
            transition back: a reinstated policy stays labelled REINSTATED for
            audit and actuarial purposes rather than being written back to
            ACTIVE. Undoing it means lapsing the policy again, which is a
            different event with its own record -- said plainly, because
            "reinstate" sounds like an undo and is not one.
          */
          reversal="A reinstated policy keeps that label permanently — it is never written back to ACTIVE. Undoing this means lapsing the policy again, as a separate event."
          confirmLabel="Reinstate policy"
          busy={reinstating.status === 'loading'}
          onConfirm={() => {
            void reinstatePolicy(policyNumber);
            setArmed(false);
          }}
          onCancel={() => setArmed(false)}
        />
      ) : (
        <Button size="sm" onClick={() => setArmed(true)}>
          <RotateCcw />
          Reinstate
        </Button>
      )}
    </div>
  );
}

