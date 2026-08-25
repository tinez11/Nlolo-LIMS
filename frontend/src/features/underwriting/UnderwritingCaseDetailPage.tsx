import { zodResolver } from '@hookform/resolvers/zod';
import { ArrowLeft } from 'lucide-react';
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { Link, useParams } from 'react-router-dom';
import { ASSESSMENT_TYPES } from '@/api/types';
import { PageHeader } from '@/components/AppShell';
import { Field } from '@/components/Field';
import { StatusBadge } from '@/components/StatusBadge';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
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
 * The only screen that can ever show a case: opened straight from
 * `OpenUnderwritingCasePage`'s own redirect, or a direct visit to a
 * previously-bookmarked url -- there is no browse-back path either through
 * this platform's own missing list/search endpoint, or through a policy this
 * case eventually issues (`GET /policies` never re-surfaces `underwritingCaseId`,
 * confirmed against the real wire DTO and the OpenAPI spec, not just the
 * internal same-named domain type).
 *
 * `decideIfPossible` runs unconditionally on every `POST /assessments`, not
 * once "enough" evidence exists -- so submitting ONE assessment IS the
 * decision, and a second call 409s (`UnderwritingCaseAlreadyDecidedException`).
 * There is no separate accept/decline/rate-up action to build a form for.
 */
export function UnderwritingCaseDetailPage() {
  const { caseId = '' } = useParams();

  const detail = useUnderwritingStore(selectCase(caseId));
  const loadCase = useUnderwritingStore((s) => s.loadCase);
  const submitAssessment = useUnderwritingStore((s) => s.submitAssessment);
  const resetSubmitAssessment = useUnderwritingStore((s) => s.resetSubmitAssessment);
  const submitting = useUnderwritingStore(selectSubmittingAssessment(caseId));
  const referCase = useUnderwritingStore((s) => s.referCase);
  const referring = useUnderwritingStore(selectReferring(caseId));

  useEffect(() => {
    if (!caseId) return;
    void loadCase(caseId);
  }, [caseId, loadCase]);

  // Same reset-on-mount discipline as every other keyed mutation resource on
  // this console: `submittingAssessment` outlives this page's own
  // mount/unmount, so a previous visit's 409 would otherwise resurface
  // immediately on a fresh navigation to the same case.
  useEffect(() => {
    if (!caseId) return;
    resetSubmitAssessment(caseId);
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
          {view?.status !== 'DECIDED' ? (
            <Panel
              title="Submit an assessment"
              subtitle="This decides the case outright -- there is no separate accept/decline step."
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
                  {submitting.status === 'loading' ? 'Submitting…' : 'Submit assessment'}
                </Button>
              </form>
            </Panel>
          ) : (
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
              </dl>
            </Panel>
          )}
        </div>

        <div className="space-y-5">
          <Panel title="Case">
            {view && (
              <dl className="px-4 pb-2">
                <Field
                  label="Applicant"
                  value={
                    view.applicantPartyId ? (
                      <Link to={`/staff/parties/${view.applicantPartyId}`} className="font-mono text-xs underline">
                        {view.applicantPartyId}
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

            {view?.referralStatus === 'NONE' && (
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
            No endpoint lists or searches underwriting cases, and a policy later issued from this
            case never re-exposes its id. Bookmark this page if you need to find it again.
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

function FormField({
  label,
  error,
  children,
}: {
  label: string;
  error?: string | undefined;
  children: React.ReactNode;
}) {
  return (
    <label className="block">
      <span className="mb-1 block text-xs font-medium text-muted-foreground">{label}</span>
      {children}
      {error && <p className="mt-1 text-[11px] text-status-danger-fg">{error}</p>}
    </label>
  );
}
