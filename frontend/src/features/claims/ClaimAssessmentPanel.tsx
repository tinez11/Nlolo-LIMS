import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { Button } from '@/components/ui/button';
import { FormField } from '@/components/FormField';
import { formatMoney } from '@/lib/money';
import { selectSubmittingAssessment, useClaimStore } from '@/store/claimStore';
import {
  blankSubmitClaimAssessmentForm,
  submitClaimAssessmentFormSchema,
  toApiRequest,
  type SubmitClaimAssessmentFormValues,
} from './submitClaimAssessmentForm';
import { Input, Textarea } from '@/components/ui/input';

/**
 * `POST /claims/{claimId}/assessments` -- `CLAIMS_ASSESSOR` role only, rendered
 * by the parent only for a claim currently REGISTERED, REOPENED, or
 * UNDER_ASSESSMENT. Unlike underwriting's single-shot assessment, a claim may
 * carry several before a decision is made (`Claim.beginAssessment()` is a no-op
 * once already UNDER_ASSESSMENT, not a 409), so this form stays usable and
 * resets itself after each successful submission rather than disappearing.
 *
 * There is no `GET` for assessment history anywhere on this platform -- the
 * response of submitting IS the only moment an assessment is ever readable, so
 * the "last submission" note below is session-memory only, not a persisted log.
 */
export function ClaimAssessmentPanel({ claimId }: { claimId: string }) {
  const submitAssessment = useClaimStore((s) => s.submitAssessment);
  const resetSubmitAssessment = useClaimStore((s) => s.resetSubmitAssessment);
  const submitting = useClaimStore(selectSubmittingAssessment(claimId));

  // Same reset-on-mount discipline as every other keyed mutation resource on
  // this console: `submittingAssessment` outlives this panel's own
  // mount/unmount, so a previous visit's rejection would otherwise resurface
  // immediately on a fresh navigation to the same claim.
  useEffect(() => {
    resetSubmitAssessment(claimId);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [claimId]);

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<SubmitClaimAssessmentFormValues>({
    resolver: zodResolver(submitClaimAssessmentFormSchema),
    defaultValues: blankSubmitClaimAssessmentForm(),
  });

  async function onSubmit(values: SubmitClaimAssessmentFormValues) {
    await submitAssessment(claimId, toApiRequest(values));
    if (useClaimStore.getState().submittingAssessment[claimId]?.status === 'success') {
      reset(blankSubmitClaimAssessmentForm());
    }
  }

  const last = submitting.status === 'success' ? submitting.data : null;

  return (
    <>
      <form className="space-y-3 p-4" onSubmit={(e) => void handleSubmit(onSubmit)(e)}>
        <FormField label="Findings" error={errors.findings?.message}>
          <Textarea
            className="min-h-20"
            placeholder="Standard risk, no adverse findings"
            {...register('findings')}
          />
        </FormField>

        <div className="grid grid-cols-[1fr_auto] gap-2">
          <FormField label="Recommended amount" error={errors.recommendedAmount?.message}>
            <Input
              placeholder="1500000.00"
              {...register('recommendedAmount')}
            />
          </FormField>
          <FormField label="Currency" error={errors.recommendedCurrency?.message}>
            <Input
              className="w-20 uppercase"
              {...register('recommendedCurrency')}
            />
          </FormField>
        </div>

        <label className="flex items-center gap-1.5 text-xs text-muted-foreground">
          <input type="checkbox" {...register('fraudIndicator')} />
          Flag for fraud review
        </label>

        {submitting.status === 'error' && submitting.error && (
          <div role="alert" className="rounded-md bg-status-danger-bg px-3 py-2 text-xs text-status-danger-fg">
            {submitting.error.detail ?? submitting.error.title}
            {submitting.error.traceId && (
              <span className="ml-2 font-mono text-[10px] opacity-80">({submitting.error.traceId})</span>
            )}
          </div>
        )}

        <Button type="submit" size="sm" variant="primary" disabled={submitting.status === 'loading'}>
          {submitting.status === 'loading' ? 'Submitting…' : 'Submit assessment'}
        </Button>
      </form>

      {last && (
        <p className="border-t border-border px-4 py-2.5 text-[11px] text-muted-foreground">
          Last submission this session: {formatMoney(last.recommendedAmount)}
          {last.fraudIndicator && ' · flagged for fraud review'}
        </p>
      )}
    </>
  );
}
