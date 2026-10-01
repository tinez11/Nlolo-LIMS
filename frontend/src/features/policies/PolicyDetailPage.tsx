import { zodResolver } from '@hookform/resolvers/zod';
import { Pause, Play, RotateCcw, Users } from 'lucide-react';
import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import { Link, useParams } from 'react-router-dom';
import { useAuth } from 'react-oidc-context';
import { canSeeFinance, readIdentity } from '@/auth/claims';
import type { Realm } from '@/auth/realms';
import { PageHeader } from '@/components/PageHeader';
import { InlineError } from '@/components/InlineError';
import { AgentName } from '@/components/AgentName';
import { PartyName } from '@/components/PartyName';
import { PREMIUM_FREQUENCY_SUFFIXES } from '@/api/types';
import { ProductName } from '@/components/ProductName';
import { StatusBadge } from '@/components/StatusBadge';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { formatDate, formatMonths } from '@/lib/dates';
import { humanizeStatus } from '@/lib/status';
import { formatMoney } from '@/lib/money';
import { isInitialLoad } from '@/store/createResourceSlice';
import {
  selectCoverage,
  selectDetail,
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
import { ValueActions } from './ValueActions';

/**
 * The categories whose policies can carry a cash value -- CashValuePlanValidator's own set. A
 * term or group policy has none, so neither surrender nor paid-up is offered on one.
 */
const VALUE_CATEGORIES: readonly string[] = ['ENDOWMENT', 'WHOLE_LIFE', 'EDUCATION_SAVINGS'];
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
import type { ReactNode } from 'react';
import { RecordTabs, type TabDef } from '@/components/RecordTabs';
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

  // isInitialLoad, not a 'loading'-only check: the load fires from an effect that
  // runs AFTER first render, so status is briefly 'idle' -- a 'loading'-only check
  // let that frame fall through toward the error branch below.
  if (isInitialLoad(detail)) {
    return <LoadingBlock label={`Loading ${policyNumber}`} />;
  }

  if (detail.data === null && detail.status === 'error' && detail.error) {
    return (
      <>
        {/* Plan 2 gave the SUCCESS branch a breadcrumb and left this one rendering the ghost
            back link it replaced, so a policy that failed to load looked like a different
            console from one that loaded -- no heading, no bar, and the old affordance. The
            error path is exactly where a person most needs to know where they are and how to
            leave. */}
        <PageHeader
          breadcrumb={[{ label: 'Policies', to: `/${realm}/policies` }]}
          title={policyNumber}
        />
        <div className="px-6 pt-6">
          <ErrorPanel error={detail.error} onRetry={() => void loadDetail(policyNumber)} />
        </div>
      </>
    );
  }

  return (
    <>
      <PageHeader
        // The breadcrumb replaces the back link that used to sit above this bar: it says
        // where the record lives as well as offering the way out, and it rides the sticky
        // bar instead of scrolling away with the first panel.
        breadcrumb={[{ label: 'Policies', to: `/${realm}/policies` }]}
        title={policyNumber}
        description={
          policy?.productId ? <ProductName productId={policy.productId} /> : undefined
        }
        // Beside the title, not in `actions`: a status is what the record IS, and actions
        // are what you can do to it. They sat in one row and read as a toolbar of four.
        status={policy?.status ? <StatusBadge kind="policy" value={policy.status} /> : undefined}
        actions={
          <>
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
            {/* Surrender is a real action now (product step 1), and it lives in the Value panel
                on the Overview tab with the gates and the second-person approval it needs. A
                page-bar button cannot carry any of that, so there is none. */}
          </>
        }
      />

      {/*
        Two columns, and the split is the point: the rail is the record, the tabs are the
        work. The rail holds only what a reader needs WHILE working in another tab -- who,
        how much, which product, when, who issued it -- and everything that is a register or
        an act is a tab.

        This replaces a vertical ordering argument that tabs made obsolete. The old stack ran
        bounded panels before unbounded ones so a twenty-row invoice ledger could not bury
        the beneficiary editor below the fold; there is no fold to fall below now, and no
        panel sits after another. What survives of that reasoning is the rule for the RAIL,
        which is still 320px and still has to stay short.

        Nothing takes `emphasis`, deliberately. Unlike a claim or an underwriting case, this
        page is not opened to perform one act -- most visits are somebody looking something
        up -- so promoting one panel would mis-state why the reader is here.
      */}
      <DetailLayout record={renderRecord()}>
        <RecordTabs label="Policy sections" tabs={policyTabs()} />
      </DetailLayout>
    </>
  );

  /**
   * The record's sections, act-first.
   *
   * Overview is what this contract covers, what state it is in, and what can be done about
   * it -- never a register. It is BUILT rather than declared because two of its three panels
   * are conditional: the offer explanation only for PROPOSED / NOT_TAKEN_UP, and Lifecycle
   * only in the staff console. Coverage is unconditional, which is what guarantees `tabs[0]`
   * always has content -- an agent on an ACTIVE policy would otherwise open onto nothing.
   *
   * No tab carries a count: `TabDef.count` is for a number the page ALREADY HOLDS, and this
   * page holds none of them -- each register fetches its own inside its panel.
   */
  function policyTabs(): TabDef[] {
    // Coverage leads Overview, and it moved here OUT of the record rail deliberately. The
    // rail is for the facts you need while working in another tab -- who, how much, which
    // product, when. What the policy actually INSURES is not a reference fact, it is the
    // first thing a reader came for, and at 320px it was a benefits list in a margin. It
    // also means Overview always has content, so the default tab is never empty.
    const overview: ReactNode[] = [
      <Panel key="coverage" title="Coverage" subtitle="Active benefits as of today">
        {renderCoverage()}
      </Panel>,
    ];

    /*
      First, above everything else, and not a badge.

      "Is this person covered?" is the single most important thing this page answers, and
      since cover began waiting for the first premium the status alone no longer answers it
      for a reader who does not already know the rule. PROPOSED looks like a normal status;
      nothing about the word tells you the customer is uninsured, or what would change that.
    */
    if (policy?.status === 'PROPOSED') {
      overview.push(
        <Panel key="not-yet" title="Not yet on cover">
          <p className="px-4 pb-4 text-xs text-muted-foreground">
            This is an offer, not a policy in force. Cover starts when the first premium
            clears — until then no claim can be settled against it. The invoices under Billing
            are what the customer pays to accept.
          </p>
        </Panel>,
      );
    }
    if (policy?.status === 'NOT_TAKEN_UP') {
      overview.push(
        <Panel key="expired" title="Offer expired unpaid">
          <p className="px-4 pb-4 text-xs text-muted-foreground">
            This offer was never taken up: no first premium arrived within the offer window, so
            it closed. Cover never started, which is why this is not a lapse — it does not
            count against persistency. A new application is needed to insure this person.
          </p>
        </Panel>,
      );
    }
    // Suspend/resume/reinstate are all hasRole('REALM_STAFF') only -- shown only in the staff
    // console, not just left to always-403 on click, the same "don't render a button that can
    // never work for this session" discipline the deferred surrender action already follows.
    if (isStaff && policy) {
      overview.push(
        <Panel key="lifecycle" title="Lifecycle">
          <LifecycleActions policyNumber={policyNumber} status={policy.status} />
        </Panel>,
      );
      // Only on a policy that could have value. A term policy has none, and a panel offering to
      // surrender nothing would be two refusals wearing the shape of an action.
      if (VALUE_CATEGORIES.includes(policy.productCategory ?? '')) {
        overview.push(
          <Panel key="value" title="Value" subtitle="Stop paying and keep reduced cover, or cash it in">
            <ValueActions policy={policy} />
          </Panel>,
        );
      }
    }

    const tabs: TabDef[] = [
      {
        value: 'overview',
        label: 'Overview',
        content: <div className="space-y-5 pt-5">{overview}</div>,
      },
    ];

    tabs.push(
      {
        value: 'beneficiaries',
        label: 'Beneficiaries',
        content: (
          <div className="pt-5">
            <Panel title="Beneficiaries">
              {policy && (
                <BeneficiariesPanel
                  policyNumber={policyNumber}
                  beneficiaries={policy.beneficiaries ?? []}
                />
              )}
            </Panel>
          </div>
        ),
      },
      {
        value: 'billing',
        label: 'Billing',
        content: (
          <div className="pt-5">
            <Panel title="Invoices" subtitle="All invoices for this policy">
              <InvoicesPanel policyNumber={policyNumber} />
            </Panel>
          </div>
        ),
      },
      {
        value: 'loans',
        label: 'Loans',
        content: (
          <div className="pt-5">
            <Panel title="Loans" subtitle="Policy loans taken against cash value">
              <LoansPanel policyNumber={policyNumber} cashValue={policy?.cashValue} />
            </Panel>
          </div>
        ),
      },
      {
        value: 'messages',
        label: 'Messages',
        content: (
          <div className="pt-5">
            <Panel title="Messages" subtitle="What this customer has been told about this policy">
              <MessagesPanel policyNumber={policyNumber} />
            </Panel>
          </div>
        ),
      },
    );

    if (canSeeReinsurance && policy) {
      tabs.push({
        value: 'reinsurance',
        label: 'Reinsurance',
        content: (
          <div className="pt-5">
            <Panel title="Reinsurance" subtitle="Cessions this policy's own coverage produced">
              <CessionsPanel policyNumber={policyNumber} />
            </Panel>
          </div>
        ),
      });
    }

    return tabs;
  }

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
                  // No count any more, and it had to go rather than be moved. It read
                  // `invoices.data?.length`, and the ONLY caller of `loadInvoices` on the
                  // platform is InvoicesPanel -- which mounts when the Billing tab is
                  // opened. So from the moment this record grew tabs the clause was absent
                  // on every arrival and appeared only after a detour through Billing. A
                  // count that is usually missing is worse than no count; the pointer to
                  // where the invoices actually are is the half that was carrying its
                  // weight, and it now names the tab rather than a panel that moved.
                  note="Single premium per borrower at the scheme's rate, invoiced when a file is accepted — see Billing."
                />
              ) : (
                <Field
                  label="Premium"
                  value={
                    <>
                      {formatMoney(policy.premium)}
                      {policy.premiumFrequency && (
                        <span className="ml-1 text-xs text-subtle-foreground">
                          {PREMIUM_FREQUENCY_SUFFIXES[policy.premiumFrequency]}
                        </span>
                      )}
                    </>
                  }
                />
              )}
              {/* The note is now only for a product that will never have one. A savings policy's
                  value is real: it is restated from the product's table as premiums are paid, and
                  0.00 there means "not yet", not "never". */}
              <Field
                label="Cash value"
                value={formatMoney(policy.cashValue)}
                {...(VALUE_CATEGORIES.includes(policy.productCategory ?? '')
                  ? {
                      note: "From the product's cash-value table, restated as premiums are paid. Nothing until the first two or three full years.",
                    }
                  : { note: 'This product carries no cash value — it is pure protection.' })}
              />
              <Field label="Issued" value={formatDate(policy.issueDate)} />
              {/*
                HOW IT CAME TO BE ISSUED (policy V24). An underwriting decision links to its case;
                an exception route -- manual issue, a scheme set up from agreed terms -- says why,
                in the issuer's own words, and who issued it, for compliance. Both used to be
                collected and discarded, so a policy could not say whether anyone underwrote it.
              */}
              {policy.underwritingCaseId && (
                <Field
                  label="Underwriting"
                  value={
                    <Link className="hover:underline" to={`/staff/underwriting/${policy.underwritingCaseId}`}>
                      The decided case
                    </Link>
                  }
                />
              )}
              {policy.issuanceBasis && (
                <Field
                  label="Issued outside underwriting"
                  value={humanizeStatus(policy.issuanceBasis)}
                  {...(policy.issuanceReason ? { note: policy.issuanceReason } : {})}
                />
              )}
              {policy.issuedByName && <Field label="Issued by" value={policy.issuedByName} />}
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
            // `humanizeStatus`, not a bare lowercase: this rendered "death" and "critical
            // illness" in a column of sentence-cased labels, which was easy to miss in a
            // 320px rail and is not once Coverage leads the Overview tab. It is also the
            // one humaniser on the platform, so a benefit type gains an acronym the day the
            // backend adds one without this line needing to know.
            label={c.benefitType ? humanizeStatus(c.benefitType) : 'Benefit'}
            value={formatMoney(c.sumAssured)}
          />
        ))}
      </dl>
    );
  }

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
        <InlineError error={suspending.error} />
      )}

      <div className="flex items-center gap-1.5">
        <Button type="submit" size="sm" variant="primary" pending={suspending.status === 'loading'}>
          Suspend policy
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
        <InlineError error={resuming.error} />
      )}
      <Button size="sm" pending={resuming.status === 'loading'} onClick={() => void resumePolicy(policyNumber)}>
        <Play />
        Resume
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
        <InlineError error={reinstating.error} />
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

