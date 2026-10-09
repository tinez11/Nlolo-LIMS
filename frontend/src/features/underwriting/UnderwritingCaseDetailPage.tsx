import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useState } from 'react';
import { getFuneralApplication } from '@/api/funeral';
import type { FuneralApplicationView } from '@/api/types';
import { FuneralApplicationSummary } from './FuneralApplicationSummary';
import { GroupFuneralProposalPanel } from './GroupFuneralProposalPanel';
import { useForm } from 'react-hook-form';
import { useAuth } from 'react-oidc-context';
import { Link, useParams } from 'react-router-dom';
import { ASSESSMENT_TYPES } from '@/api/types';
import type { DecideRequest } from '@/api/types';
import { readIdentity, staffRoles } from '@/auth/claims';
import { PageHeader } from '@/components/PageHeader';
import { Field } from '@/components/Field';
import { PartyName } from '@/components/PartyName';
import { ProductName } from '@/components/ProductName';
import { StatusBadge } from '@/components/StatusBadge';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { FormField } from '@/components/FormField';
import { DisclosurePanel } from './DisclosurePanel';
import { formatDate, formatInstant } from '@/lib/dates';
import { isInitialLoad } from '@/store/createResourceSlice';
import {
  selectCase,
  selectDeciding,
  selectReferring,
  selectSubmittingAssessment,
  useUnderwritingStore,
} from '@/store/underwritingStore';
import {
  blankSubmitAssessmentForm,
  submitAssessmentFormSchema,
  toApiRequest,
  type SubmitAssessmentFormValues,
} from './submitAssessmentForm';
import { DecisionPanel } from './DecisionPanel';
import { useAnnuityStore } from '@/store/annuityStore';
import { Panel } from '@/components/Panel';
import { DetailLayout } from '@/components/DetailLayout';
import { Input, Select, Textarea } from '@/components/ui/input';
import { InlineError } from '@/components/InlineError';
import { recordSale } from '@/api/underwriting';
import { BranchSelect } from '@/components/BranchSelect';
import type { ApiError } from '@/lib/apiError';
import { CHANNEL_LABEL, channelLabel } from '@/lib/ifrs17';
import { VALIDATE_ON_TOUCH } from '@/lib/formTiming';

/**
 * Reached from `OpenUnderwritingCasePage`'s own redirect, a direct visit to a
 * bookmarked url, or -- now that `GET /underwriting/cases` exists -- a real
 * click-through from the Underwriting queue's drawer. Still not reachable
 * through a policy this case eventually issues, though: `GET /policies` never
 * re-surfaces `underwritingCaseId` (confirmed against the real wire DTO and
 * the OpenAPI spec, not just the internal same-named domain type).
 *
 * Two panels, because assessing and deciding are two acts. `POST /assessments` records
 * EVIDENCE and refreshes the rules engine's recommendation; `POST /decision` is what settles
 * the case and, on an acceptance, issues the policy.
 *
 * They were one act until recently, and this page said so: submitting one assessment ran the
 * engine, wrote its verdict into the decision fields and put a contract in force, and the
 * confirmation dialog told the user "there is no separate accept, decline or rate-up step".
 * That was accurate and it was the defect -- a placeholder algorithm was the sole author of
 * every underwriting decision on the platform, with no override and no way back from a
 * mistyped risk score.
 *
 * A second assessment on a DECIDED case still 409s
 * (`UnderwritingCaseAlreadyDecidedException`): a decided case is closed to further evidence.
 *
 * ONE EXCEPTION: a POSTPONED case accepts further assessments. It is the outcome that means
 * "not decided yet -- come back with more evidence", and treating it as final made it the only
 * outcome that could never be resolved. So the assessment form below stays available on a
 * postponed case, and the engine weighs the LATEST assessment per type rather than the worst
 * one ever recorded -- otherwise a case postponed at 95 would re-postpone forever.
 *
 * Assessment/decision/referral are gated on the current user's OWN token roles
 * (`staffRoles`), same convenience-only decode `ClaimDetailPage` already uses
 * for its own action panels -- the backend's `@PreAuthorize('UNDERWRITER')`
 * remains the real authority, this only avoids showing a staff user (e.g.
 * finance, customer-service) a live form that would 403. A non-decided case
 * viewed by a non-underwriter renders neither panel; that is correct, not a
 * missing feature.
 *
 * SENIOR_UNDERWRITER is a second, narrower gate INSIDE the decision panel rather than on it:
 * a junior may record the decision the engine recommended and may not record any other. That
 * distinction cannot be made here, because it depends on the outcome being chosen.
 */
export function UnderwritingCaseDetailPage() {
  const { caseId = '' } = useParams();
  const auth = useAuth();
  const identity = readIdentity(auth.user?.access_token);
  const roles = staffRoles(identity);

  const detail = useUnderwritingStore(selectCase(caseId));
  // Family funeral cover: null for every case that is not a funeral plan's (its 404, normalised).
  const [funeralApplication, setFuneralApplication] = useState<FuneralApplicationView | null>(null);
  useEffect(() => {
    if (!caseId) return undefined;
    let live = true;
    getFuneralApplication(caseId).then((a) => { if (live) setFuneralApplication(a); }, () => undefined);
    return () => { live = false; };
  }, [caseId]);
  const loadCase = useUnderwritingStore((s) => s.loadCase);
  const submitAssessment = useUnderwritingStore((s) => s.submitAssessment);
  const resetSubmitAssessment = useUnderwritingStore((s) => s.resetSubmitAssessment);
  const decide = useUnderwritingStore((s) => s.decide);
  const resetDecide = useUnderwritingStore((s) => s.resetDecide);
  const deciding = useUnderwritingStore(selectDeciding(caseId));
  const submitting = useUnderwritingStore(selectSubmittingAssessment(caseId));
  const referCase = useUnderwritingStore((s) => s.referCase);
  const resetReferCase = useUnderwritingStore((s) => s.resetReferCase);
  const referring = useUnderwritingStore(selectReferring(caseId));

  // An annuity case carries its choice (product step 5); null for every other case.
  const annuityChoice = useAnnuityStore((s) => s.choice[caseId]);
  const loadAnnuityChoice = useAnnuityStore((s) => s.loadChoice);
  // A deferred annuity case carries a retirement age instead (D2); null for every other case.
  const deferredChoice = useAnnuityStore((s) => s.deferredChoice[caseId]);
  const loadDeferredChoice = useAnnuityStore((s) => s.loadDeferredChoice);

  useEffect(() => {
    if (!caseId) return;
    void loadCase(caseId);
    void loadAnnuityChoice(caseId);
    void loadDeferredChoice(caseId);
  }, [caseId, loadCase, loadAnnuityChoice, loadDeferredChoice]);
  const choicesRead = !!annuityChoice && !isInitialLoad(annuityChoice) && !!deferredChoice && !isInitialLoad(deferredChoice);
  const annuityCase = annuityChoice?.data != null || deferredChoice?.data != null;

  // Same reset-on-mount discipline as every other keyed mutation resource on
  // this console: `submittingAssessment`/`referring` outlive this page's own
  // mount/unmount, so a previous visit's failure would otherwise resurface
  // immediately on a fresh navigation to the same case -- a real path now,
  // whether by bookmark or by revisiting through the Underwriting queue.
  useEffect(() => {
    if (!caseId) return;
    resetSubmitAssessment(caseId);
    resetDecide(caseId);
    resetReferCase(caseId);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [caseId]);

  const {
    register,
    handleSubmit,
    reset: resetAssessmentForm,
    formState: { errors },
  } = useForm<SubmitAssessmentFormValues>({
    ...VALIDATE_ON_TOUCH,
    resolver: zodResolver(submitAssessmentFormSchema),
    defaultValues: blankSubmitAssessmentForm(),
  });

  async function commitAssessment(values: SubmitAssessmentFormValues) {
    await submitAssessment(caseId, toApiRequest(values));
    if (useUnderwritingStore.getState().submittingAssessment[caseId]?.status === 'success') {
      resetAssessmentForm(blankSubmitAssessmentForm());
    }
  }

  async function commitDecision(request: DecideRequest) {
    await decide(caseId, request);
  }

  const view = detail.data;
  // POSTPONED is a decision in status only: the engine returns it asking for further medical
  // evidence, so the case is still open in every sense that matters to an underwriter. Named
  // once because four separate places have to agree about it -- whether the form renders,
  // what it is called, what it says, and what its button claims to do.
  const isPostponed = view?.decisionOutcome === 'POSTPONED';

  if (isInitialLoad(detail)) {
    return <LoadingBlock label={`Loading case ${caseId}`} />;
  }

  if (detail.data === null && detail.status === 'error' && detail.error) {
    return (
      <>
        {/* The bar renders on the error path too, so a record that fails to load keeps its
            heading and its way out instead of leaving a bare panel. */}
        <PageHeader breadcrumb={[{ label: 'Underwriting', to: '/staff/underwriting' }]} title="Underwriting case" />
        <div className="px-6 pt-6">
          <ErrorPanel error={detail.error} onRetry={() => void loadCase(caseId)} />
        </div>
      </>
    );
  }

  return (
    <>
      <PageHeader
        breadcrumb={[{ label: 'Underwriting', to: '/staff/underwriting' }]}
        title="Underwriting case"
        description={<span className="font-mono text-xs">{caseId}</span>}
        actions={view?.status && <StatusBadge kind="underwritingCase" value={view.status} />}
      />

      {/* The record rail carries the applicant and the life assured, and it is pinned
          -- on a case where those two are different people, that distinction is the
          whole reason the panel exists, and it was scrolling away exactly when the
          declarations below were being read against it. */}
      <DetailLayout record={renderRecord()}>
        {/*
          WHAT IS BEING DECIDED, when it is not a proposal. A scheme member's free-cover-limit
          evidence case grants or refuses that member's excess, and issues no policy -- it used
          to read exactly like a new proposal, and accepting it issued the member a policy of
          their own. First, so nobody decides it without knowing what it is.
        */}
        {view?.evidenceForPolicyNumber && (
          <Panel
            title="Evidence for a scheme member"
            subtitle="This decides one member's cover above the scheme's free cover limit. It issues no policy."
          >
            <div className="space-y-1 px-4 py-3 text-xs">
              <p>
                Member of{' '}
                <Link
                  className="font-mono hover:underline"
                  to={`/staff/group-schemes/${encodeURIComponent(view.evidenceForPolicyNumber)}`}
                >
                  {view.evidenceForPolicyNumber}
                </Link>
                . Accept to cover them for their full benefit from the decision date; decline to
                keep them covered up to the limit.
              </p>
              <p className="text-muted-foreground">
                A loading is not available: one member of a scheme has no premium of their own. The
                scheme&apos;s premium changes at renewal.
              </p>
            </div>
          </Panel>
        )}
        {annuityChoice?.data && (
          <Panel
            title="Annuity purchase"
            subtitle="The sum assured is the purchase price. The income is locked from the rate table when the premium arrives."
          >
            <dl className="px-4 pb-2">
              <Field label="Form" value={annuityChoice.data.formCode} />
              <Field label="Frequency" value={annuityChoice.data.frequency} />
              {annuityChoice.data.jointLifePartyId && (
                <Field label="Joint life" value={<span className="font-mono text-xs">{annuityChoice.data.jointLifePartyId}</span>} />
              )}
              {annuityChoice.data.ageEvidenceConfirmedBy && (
                <Field
                  label="Proof of age"
                  value={`Confirmed by ${annuityChoice.data.ageEvidenceConfirmedBy}, ${formatInstant(annuityChoice.data.ageEvidenceConfirmedAt)}`}
                />
              )}
            </dl>
          </Panel>
        )}
        {view?.groupScheme && (
          <GroupFuneralProposalPanel caseId={caseId}
            canReplace={view.status !== 'DECIDED' && roles.UNDERWRITER} />
        )}
        {funeralApplication && (
          <Panel title="Funeral plan" subtitle="The plan, the family it covers, and what each life costs today">
            <div className="px-4 pb-4">
              <FuneralApplicationSummary application={funeralApplication} />
            </div>
          </Panel>
        )}
        {deferredChoice?.data && (
          <Panel
            title="Pension"
            subtitle="The sum assured is the contribution per payment. The form is chosen when it vests, at the rates in force that day."
          >
            <dl className="px-4 pb-2">
              <Field label="Retirement age" value={deferredChoice.data.retirementAge} />
              <Field label="Vests on" value={formatDate(deferredChoice.data.targetDate)} />
              {deferredChoice.data.ageEvidenceConfirmedBy && (
                <Field
                  label="Proof of age"
                  value={`Confirmed by ${deferredChoice.data.ageEvidenceConfirmedBy}, ${formatInstant(deferredChoice.data.ageEvidenceConfirmedAt)}`}
                />
              )}
            </dl>
          </Panel>
        )}
        {/*
          THE ACCEPTANCE THAT PRODUCED NOTHING.

          Automatic issuance runs in an AFTER_COMMIT listener, so when it fails the decision has
          already committed and cannot be rolled back. Until this panel existed, such a case was
          indistinguishable on screen from one whose policy was created: same DECIDED status,
          same ACCEPT badge, same everything. The only record was a stack trace in a log file.

          One sat like that in production -- a product whose rating multiplier was zero, so the
          premium computed to nothing and a CHECK constraint refused it -- until somebody
          happened to ask why the customer had no policy.

          FIRST IN THE COLUMN, above the decision it contradicts, because a reader who sees
          "Accept" and stops reading has been told the opposite of what happened.
        */}
        {view?.issuanceFailureReason && (
          <Panel title="No policy was issued">
            <div
              role="alert"
              className="mx-4 mb-4 rounded-md bg-status-danger-bg px-3 py-2 text-xs text-status-danger-fg"
            >
              <p className="font-medium">
                This case was decided, but automatic issuance failed and no policy exists.
              </p>
              <p className="mt-1">{view.issuanceFailureReason}</p>
              <p className="mt-2">
                Recorded {formatInstant(view.issuanceFailedAt)}. The decision itself stands.
                Correct what the message names, then issue the policy by hand from Policies — this
                will not retry on its own.
              </p>
            </div>
          </Panel>
        )}

        {view?.status === 'DECIDED' && (
          <Panel title="Decision">
            <dl className="px-4 pb-2">
              <Field
                label="Outcome"
                value={<StatusBadge kind="underwritingDecision" value={view.decisionOutcome} />}
              />
              {view.decisionOutcome === 'LOADED' && (
                <Field
                  label="Loading"
                  value={
                    view.decisionLoadingPercent != null ? `${view.decisionLoadingPercent}%` : '—'
                  }
                />
              )}
              {view.decisionOutcome === 'DECLINED' && (
                <Field label="Reason" value={view.decisionDeclineReason ?? '—'} />
              )}
              <Field label="Decided" value={formatInstant(view.decisionDecidedAt)} />
              {isPostponed && (
                <Field
                  label="Awaiting"
                  value="Further evidence"
                  note="A postponed case is not finished. Submit another assessment below and it will be decided again."
                />
              )}
            </dl>
          </Panel>
        )}

        {/*
          The decision, below the evidence panel and above the disclosures, in the order the
          work happens: assess, then decide.

          Same visibility rule as the assessment form -- an undecided case, or a POSTPONED
          one, which is a decision in status only. A POSTPONED case can be decided again once
          the evidence it asked for arrives.
        */}
        {/* Waits for the choice read: the form's outcomes and checks differ on an annuity, and a
            form opened before the answer would keep the wrong ones. Keyed so it rebuilds if it changes. */}
        {view && (view.status !== 'DECIDED' || isPostponed) && choicesRead && (
          <DecisionPanel
            key={annuityCase ? 'annuity' : 'risk'}
            annuity={annuityCase}
            view={view}
            deciding={deciding}
            canDecide={roles.UNDERWRITER}
            isSenior={roles.SENIOR_UNDERWRITER}
            callerSubject={identity.subject}
            onDecide={(request) => void commitDecision(request)}
          />
        )}

        {/* Shown while the case is undecided AND when it is POSTPONED, which is a decision
            in status only: it means "come back with more evidence", so refusing further
            assessments made it the one outcome that could never resolve. */}
        {(view?.status !== 'DECIDED' || isPostponed) && roles.UNDERWRITER ? (
          <Panel
            emphasis
            title={isPostponed ? 'Submit further evidence' : 'Submit an assessment'}
            subtitle="Evidence for the decision. This records the finding and refreshes the engine's recommendation; it does not decide the case."
          >
            <form
              className="space-y-4 p-4"
              onSubmit={(e) => void handleSubmit(commitAssessment)(e)}
            >
              <FormField label="Assessment type">
                <Select
                  {...register('assessmentType')}
                >
                  {ASSESSMENT_TYPES.map((t) => (
                    <option key={t} value={t}>
                      {t}
                    </option>
                  ))}
                </Select>
              </FormField>

              <FormField label="Findings" error={errors.findings?.message}>
                <Textarea
                  className="min-h-20"
                  placeholder="Standard risk, no adverse findings"
                  {...register('findings')}
                />
              </FormField>

              <FormField label="Risk score (optional)" error={errors.riskScore?.message}>
                <Input
                  className="w-32"
                  placeholder="10"
                  {...register('riskScore')}
                />
              </FormField>

              {submitting.status === 'error' && submitting.error && (
                <InlineError error={submitting.error} />
              )}

              {/*
                No confirmation step any more, and the removal is the point.

                This used to raise a ConfirmAct reading "Submitting a MEDICAL assessment
                decides the case outright ... The decision is final: a second assessment on a
                decided case is refused, and an acceptance issues a policy automatically."
                Every word of that was true, and it described the defect: an assessment ran a
                placeholder rules engine, settled the case and put a contract in force.

                Recording evidence is now an ordinary, reversible act -- submit another and
                the recommendation recomputes -- so demanding confirmation for it would be
                ceremony. The confirmation belongs on the decision, which is where the
                consequence now lives.
              */}
              <Button type="submit" variant="primary" pending={submitting.status === 'loading'}>
                {isPostponed ? 'Submit further evidence' : 'Submit assessment'}
              </Button>
            </form>
          </Panel>
        ) : null}

        {/* Recorded by whoever took the proposal -- agents included -- not gated on the
            UNDERWRITER role that gates assessment above. Asking the questions and deciding
            the case are different jobs done by different people.

            This is the evidence a contestability review reads. Claims computes and shows
            `requiresContestabilityReview` on every claim; until disclosures existed there
            was nothing behind it. */}
        <Panel
          title="Declarations"
          subtitle="What the applicant declared. Read back on a claim, so it records the question as it was put."
        >
          <DisclosurePanel caseId={caseId} />
        </Panel>
      </DetailLayout>
    </>
  );

  function renderRecord() {
    return (
      <>
        <Panel title="Case">
          {view && (
            <dl className="px-4 pb-2">
              <Field
                label="Proposal"
                value={
                  view.proposalNumber ? (
                    <span className="font-mono text-xs">{view.proposalNumber}</span>
                  ) : (
                    '—'
                  )
                }
                {...(view.proposalNumber ? {} : { note: 'Opened before proposal numbers existed.' })}
              />
              <Field
                label="Applicant"
                value={
                  view.applicantPartyId ? (
                    <Link to={`/staff/parties/${view.applicantPartyId}`} className="underline">
                      <PartyName partyId={view.applicantPartyId} />
                    </Link>
                  ) : (
                    '—'
                  )
                }
                // "Applicant" is who proposed; the life assured below is whose
                // mortality is being assessed. On most cases they are the same person,
                // and where they are not, that is the whole point of the distinction.
                {...(view.lifeAssuredPartyId && view.lifeAssuredPartyId !== view.applicantPartyId
                  ? { note: 'Proposing on someone else’s life.' }
                  : {})}
              />
              <Field
                label="Life assured"
                value={
                  view.lifeAssuredPartyId ? (
                    <Link to={`/staff/parties/${view.lifeAssuredPartyId}`} className="underline">
                      <PartyName partyId={view.lifeAssuredPartyId} />
                    </Link>
                  ) : (
                    '—'
                  )
                }
              />
              {view.branch && <Field label="Branch (as written)" value={view.branch} />}
              <Field label="Sales channel" value={channelLabel(view.salesChannel)} />
              <Field label="Sale branch" value={view.branchCode ?? 'Not named yet'} />
              {view.sourceOfBusiness && (
                <Field label="Source of business" value={view.sourceOfBusiness} />
              )}
              {view.proposedCommencementDate && (
                <Field
                  label="Proposed commencement"
                  value={formatDate(view.proposedCommencementDate)}
                />
              )}
              <Field
                label="Product"
                value={view.productId ? <ProductName productId={view.productId} /> : '—'}
              />
              <Field
                label="Referral"
                value={<StatusBadge kind="referral" value={view.referralStatus} />}
              />
            </dl>
          )}

          {view && (
            <SaleSection
              caseId={caseId}
              salesChannel={view.salesChannel ?? 'DIRECT'}
              branchCode={view.branchCode ?? ''}
              locked={view.saleLockedAt != null}
              onSaved={() => void loadCase(caseId)}
            />
          )}

          {view?.referralStatus === 'NONE' && roles.UNDERWRITER && (
            <div className="px-4 pb-4">
              <Button
                size="sm"
                variant="outline"
                pending={referring.status === 'loading'}
                onClick={() => void referCase(caseId)}
              >
                Refer to senior underwriter
              </Button>
              {referring.status === 'error' && referring.error && (
                <p className="mt-2 text-xs text-status-danger-fg">
                  {referring.error.detail ?? referring.error.title}
                </p>
              )}
            </div>
          )}
        </Panel>

        <div className="rounded-lg border border-dashed border-border bg-surface-muted px-4 py-3 text-xs text-muted-foreground">
          Browsable again from the{' '}
          <Link to=".." relative="path" className="underline">
            Underwriting queue
          </Link>
          . A policy later issued from this case still never re-exposes its id, though —
          bookmark this page if you need direct access without going through the queue.
        </div>
      </>
    );
  }
}

/**
 * The channel and branch of the sale (IFRS 17 I2): editable until the policy is issued, which fixes them -- the
 * policy carries them for good and they become posting dimensions.
 */
function SaleSection({
  caseId,
  salesChannel,
  branchCode,
  locked,
  onSaved,
}: {
  caseId: string;
  salesChannel: string;
  branchCode: string;
  locked: boolean;
  onSaved: () => void;
}) {
  const [editing, setEditing] = useState(false);
  const [channel, setChannel] = useState(salesChannel);
  const [branch, setBranch] = useState(branchCode);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  if (locked) {
    return <p className="px-4 pb-3 text-xs text-muted-foreground">Sale fixed when the policy was issued.</p>;
  }
  if (!editing) {
    return (
      <div className="px-4 pb-3">
        <Button
          size="sm"
          variant="ghost"
          onClick={() => {
            setChannel(salesChannel);
            setBranch(branchCode);
            setEditing(true);
          }}
        >
          Change sale
        </Button>
      </div>
    );
  }
  return (
    <form
      aria-label="Change sale"
      className="space-y-3 px-4 pb-4"
      onSubmit={(e) => {
        e.preventDefault();
        setBusy(true);
        setError(null);
        recordSale(caseId, channel, branch)
          .then(() => {
            setEditing(false);
            onSaved();
          })
          .catch((err: unknown) => setError(err as ApiError))
          .finally(() => setBusy(false));
      }}
    >
      {error && <InlineError error={error} />}
      <FormField label="Sales channel">
        <Select value={channel} onChange={(e) => setChannel(e.target.value)}>
          {Object.keys(CHANNEL_LABEL).map((c) => (
            <option key={c} value={c}>
              {CHANNEL_LABEL[c]}
            </option>
          ))}
        </Select>
      </FormField>
      <FormField label="Sale branch">
        <BranchSelect value={branch} onChange={setBranch} allowNone noneLabel="Choose a branch" />
      </FormField>
      <div className="flex gap-2">
        <Button type="submit" size="sm" pending={busy} disabled={branch === ''}>
          Save sale
        </Button>
        <Button type="button" size="sm" variant="ghost" disabled={busy} onClick={() => setEditing(false)}>
          Cancel
        </Button>
      </div>
    </form>
  );
}


