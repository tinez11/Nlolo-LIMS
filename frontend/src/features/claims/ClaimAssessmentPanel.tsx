import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { Button } from '@/components/ui/button';
import { FormField } from '@/components/FormField';
import { formatMoney } from '@/lib/money';
import {
  selectClaimableCover,
  selectSubmittingAssessment,
  useClaimStore,
} from '@/store/claimStore';
import {
  blankSubmitClaimAssessmentForm,
  submitClaimAssessmentSchema,
  toApiRequest,
  type SubmitClaimAssessmentFormValues,
} from './submitClaimAssessmentForm';
import { Input, Textarea } from '@/components/ui/input';
import { InlineError } from '@/components/InlineError';

/**
 * `POST /claims/{claimId}/assessments` -- `CLAIMS_ASSESSOR` role only, rendered
 * by the parent only for a claim currently REGISTERED, REOPENED, or
 * UNDER_ASSESSMENT. Unlike underwriting's single-shot assessment, a claim may
 * carry several before a decision is made (`Claim.beginAssessment()` is a no-op
 * once already UNDER_ASSESSMENT, not a 409), so this form stays usable and
 * resets itself after each successful submission rather than disappearing.
 *
 * The "last submission" note below is session-memory only. `GET
 * /claims/{claimId}/assessments` now exists, but it answers a different
 * question -- what has been recorded on this claim by anyone -- and is read by
 * the settlement panel, where the decision is made. What this note reports is
 * narrower and still worth keeping: what YOU just sent, so a double-submit is
 * visible.
 */
export function ClaimAssessmentPanel({ claimId }: { claimId: string }) {
  const submitAssessment = useClaimStore((s) => s.submitAssessment);
  const resetSubmitAssessment = useClaimStore((s) => s.resetSubmitAssessment);
  const submitting = useClaimStore(selectSubmittingAssessment(claimId));
  const loadClaimableCover = useClaimStore((s) => s.loadClaimableCover);
  const coverResource = useClaimStore(selectClaimableCover(claimId));

  // Same reset-on-mount discipline as every other keyed mutation resource on
  // this console: `submittingAssessment` outlives this panel's own
  // mount/unmount, so a previous visit's rejection would otherwise resurface
  // immediately on a fresh navigation to the same claim.
  useEffect(() => {
    resetSubmitAssessment(claimId);
    void loadClaimableCover(claimId);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [claimId]);

  const cover = coverResource.data?.claimableCover ?? null;

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors, isDirty },
  } = useForm<SubmitClaimAssessmentFormValues>({
    resolver: zodResolver(submitClaimAssessmentSchema(cover)),
    defaultValues: blankSubmitClaimAssessmentForm(),
  });

  /*
    The recommendation starts at the full cover, which is the correct opening
    position for the commonest case by far: a death claim on credit life pays
    the outstanding balance exactly. An assessor who finds a reason to recommend
    less types it; one who does not is not made to transcribe a figure the
    platform already knows.

    Guarded on `isDirty` so a slow read cannot overwrite a figure already typed.
  */
  useEffect(() => {
    if (isDirty || !cover) return;
    reset(blankSubmitClaimAssessmentForm(cover));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [cover?.amount, cover?.currencyCode]);

  async function onSubmit(values: SubmitClaimAssessmentFormValues) {
    await submitAssessment(claimId, toApiRequest(values));
    if (useClaimStore.getState().submittingAssessment[claimId]?.status === 'success') {
      reset(blankSubmitClaimAssessmentForm(cover));
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
          <FormField
            label="Recommended amount"
            error={errors.recommendedAmount?.message}
            // Was a blank field with a placeholder of 1500000.00 -- a number
            // nobody chose, on a form whose entire output is a number.
            hint={
              cover
                ? `Covered for ${formatMoney(cover)} — the most this claim can pay`
                : coverResource.status === 'error'
                  ? 'Could not read what this claim is covered for'
                  : undefined
            }
          >
            <Input {...register('recommendedAmount')} />
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
          <InlineError error={submitting.error} />
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
