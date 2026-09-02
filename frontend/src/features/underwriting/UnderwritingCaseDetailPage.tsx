import { zodResolver } from '@hookform/resolvers/zod';
import { ArrowLeft } from 'lucide-react';
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { useAuth } from 'react-oidc-context';
import { Link, useParams } from 'react-router-dom';
import { ASSESSMENT_TYPES } from '@/api/types';
import { readIdentity, staffRoles } from '@/auth/claims';
import { PageHeader } from '@/components/PageHeader';
import { Field } from '@/components/Field';
import { PartyName } from '@/components/PartyName';
import { StatusBadge } from '@/components/StatusBadge';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { FormField } from '@/components/FormField';
import { DisclosurePanel } from './DisclosurePanel';
import { formatInstant } from '@/lib/dates';
import { isInitialLoad } from '@/store/createResourceSlice';
import {
  selectCase,
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

/**
 * Reached from `OpenUnderwritingCasePage`'s own redirect, a direct visit to a
 * bookmarked url, or -- now that `GET /underwriting/cases` exists -- a real
 * click-through from the Underwriting queue's drawer. Still not reachable
 * through a policy this case eventually issues, though: `GET /policies` never
 * re-surfaces `underwritingCaseId` (confirmed against the real wire DTO and
 * the OpenAPI spec, not just the internal same-named domain type).
 *
 * `decideIfPossible` runs unconditionally on every `POST /assessments`, not
 * once "enough" evidence exists -- so submitting ONE assessment IS the
 * decision, and a second call 409s (`UnderwritingCaseAlreadyDecidedException`).
 * There is no separate accept/decline/rate-up action to build a form for.
 *
 * ONE EXCEPTION: a POSTPONED case accepts further assessments. It is the outcome that means
 * "not decided yet -- come back with more evidence", and treating it as final made it the only
 * outcome that could never be resolved. So the assessment form below stays available on a
 * postponed case, and the engine weighs the LATEST assessment per type rather than the worst
 * one ever recorded -- otherwise a case postponed at 95 would re-postpone forever.
 *
 * Assessment/referral are gated on the current user's OWN token roles
 * (`staffRoles`), same convenience-only decode `ClaimDetailPage` already uses
 * for its own action panels -- the backend's `@PreAuthorize('UNDERWRITER')`
 * remains the real authority, this only avoids showing a staff user (e.g.
 * finance, customer-service) a live form that would 403. A non-decided case
 * viewed by a non-underwriter renders neither panel; that is correct, not a
 * missing feature.
 */
export function UnderwritingCaseDetailPage() {
  const { caseId = '' } = useParams();
  const auth = useAuth();
  const identity = readIdentity(auth.user?.access_token);
  const roles = staffRoles(identity);

  const detail = useUnderwritingStore(selectCase(caseId));
  const loadCase = useUnderwritingStore((s) => s.loadCase);
  const submitAssessment = useUnderwritingStore((s) => s.submitAssessment);
  const resetSubmitAssessment = useUnderwritingStore((s) => s.resetSubmitAssessment);
  const submitting = useUnderwritingStore(selectSubmittingAssessment(caseId));
  const referCase = useUnderwritingStore((s) => s.referCase);
  const resetReferCase = useUnderwritingStore((s) => s.resetReferCase);
  const referring = useUnderwritingStore(selectReferring(caseId));

  useEffect(() => {
    if (!caseId) return;
    void loadCase(caseId);
  }, [caseId, loadCase]);

  // Same reset-on-mount discipline as every other keyed mutation resource on
  // this console: `submittingAssessment`/`referring` outlive this page's own
  // mount/unmount, so a previous visit's failure would otherwise resurface
  // immediately on a fresh navigation to the same case -- a real path now,
  // whether by bookmark or by revisiting through the Underwriting queue.
  useEffect(() => {
    if (!caseId) return;
    resetSubmitAssessment(caseId);
    resetReferCase(caseId);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [caseId]);

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<SubmitAssessmentFormValues>({
    resolver: zodResolver(submitAssessmentFormSchema),
    defaultValues: blankSubmitAssessmentForm(),
  });

  async function onSubmit(values: SubmitAssessmentFormValues) {
    await submitAssessment(caseId, toApiRequest(values));
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
      <div className="px-6 pt-6">
        <BackLink />
        <ErrorPanel error={detail.error} onRetry={() => void loadCase(caseId)} />
      </div>
    );
  }

  return (
    <>
      <div className="px-6 pt-6">
        <BackLink />
      </div>

      <PageHeader
        title="Underwriting case"
        description={<span className="font-mono text-xs">{caseId}</span>}
        actions={view?.status && <StatusBadge kind="underwritingCase" value={view.status} />}
      />

      <div className="grid gap-5 px-6 pb-8 lg:grid-cols-[minmax(0,1fr)_320px]">
        <div className="space-y-5">
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

          {/* Shown while the case is undecided AND when it is POSTPONED, which is a decision
              in status only: the engine returns it asking for further medical evidence, so
              refusing further assessments made it the one outcome that could never resolve. */}
          {(view?.status !== 'DECIDED' || isPostponed) && roles.UNDERWRITER ? (
            <Panel
              title={isPostponed ? 'Submit further evidence' : 'Submit an assessment'}
              subtitle={
                isPostponed
                  ? 'The case is postponed pending evidence. This re-decides it on the latest assessment of each type.'
                  : 'This decides the case outright -- there is no separate accept/decline step.'
              }
            >
              <form
                className="space-y-4 p-4"
                onSubmit={(e) => void handleSubmit(onSubmit)(e)}
              >
                <FormField label="Assessment type">
                  <select
                    className="h-9 w-full rounded-md border border-input bg-surface px-2.5 text-sm"
                    {...register('assessmentType')}
                  >
                    {ASSESSMENT_TYPES.map((t) => (
                      <option key={t} value={t}>
                        {t}
                      </option>
                    ))}
                  </select>
                </FormField>

                <FormField label="Findings" error={errors.findings?.message}>
                  <textarea
                    className="min-h-20 w-full rounded-md border border-input bg-surface px-2.5 py-2 text-sm"
                    placeholder="Standard risk, no adverse findings"
                    {...register('findings')}
                  />
                </FormField>

                <FormField label="Risk score (optional)" error={errors.riskScore?.message}>
                  <input
                    className="h-9 w-32 rounded-md border border-input bg-surface px-2.5 text-sm"
                    placeholder="10"
                    {...register('riskScore')}
                  />
                </FormField>

                {submitting.status === 'error' && submitting.error && (
                  <div
                    role="alert"
                    className="rounded-md bg-status-danger-bg px-3 py-2 text-xs text-status-danger-fg"
                  >
                    {submitting.error.detail ?? submitting.error.title}
                    {submitting.error.traceId && (
                      <span className="ml-2 font-mono text-[10px] opacity-80">
                        ({submitting.error.traceId})
                      </span>
                    )}
                  </div>
                )}

                <Button type="submit" variant="primary" disabled={submitting.status === 'loading'}>
                  {submitting.status === 'loading'
                    ? 'Submitting…'
                    : isPostponed
                      ? 'Submit further evidence'
                      : 'Submit assessment'}
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
        </div>

        <div className="space-y-5">
          <Panel title="Case">
            {view && (
              <dl className="px-4 pb-2">
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
                />
                <Field
                  label="Product"
                  value={<span className="font-mono text-xs">{view.productId ?? '—'}</span>}
                  note="No product-by-id endpoint exists either"
                />
                <Field
                  label="Referral"
                  value={<StatusBadge kind="referral" value={view.referralStatus} />}
                />
              </dl>
            )}

            {view?.referralStatus === 'NONE' && roles.UNDERWRITER && (
              <div className="px-4 pb-4">
                <Button
                  size="sm"
                  variant="outline"
                  disabled={referring.status === 'loading'}
                  onClick={() => void referCase(caseId)}
                >
                  {referring.status === 'loading' ? 'Referring…' : 'Refer to senior underwriter'}
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
            . A policy later issued from this case still never re-exposes its id, though --
            bookmark this page if you need direct access without going through the queue.
          </div>
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
        Open another case
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
