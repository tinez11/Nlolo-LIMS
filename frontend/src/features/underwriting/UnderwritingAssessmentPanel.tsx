import { zodResolver } from '@hookform/resolvers/zod';
import { useState } from 'react';
import { useForm, useWatch } from 'react-hook-form';
import { ASSESSMENT_TYPES } from '@/api/types';
import { FormField } from '@/components/FormField';
import { InlineError } from '@/components/InlineError';
import { Panel } from '@/components/Panel';
import { Receipt } from '@/components/Receipt';
import { StatusBadge } from '@/components/StatusBadge';
import { Button } from '@/components/ui/button';
import { Input, Select, Textarea } from '@/components/ui/input';
import { VALIDATE_ON_TOUCH } from '@/lib/formTiming';
import { humanizeStatus } from '@/lib/status';
import { selectSubmittingAssessment, useUnderwritingStore } from '@/store/underwritingStore';
import {
  RISK_SCORE_BANDS,
  blankSubmitAssessmentForm,
  scoreRecommendation,
  submitAssessmentFormSchema,
  toApiRequest,
  type SubmitAssessmentFormValues,
} from './submitAssessmentForm';

/**
 * Recording evidence on an underwriting case (moved out of the case page 2026-11-07). The claims assessment panel's
 * shape: the form, then -- once it is recorded -- a receipt of what was sent and what the case now recommends, with
 * another assessment one click away. The form used to reset in place, so Submit left a screen that looked unchanged.
 *
 * The risk score says its scale and bands, and what the typed score alone would recommend: the underwriter was given
 * a bare "Risk score" box with a grey 10 in it.
 */
export function UnderwritingAssessmentPanel({ caseId, isPostponed }: { caseId: string; isPostponed: boolean }) {
  const submitAssessment = useUnderwritingStore((s) => s.submitAssessment);
  const resetSubmitAssessment = useUnderwritingStore((s) => s.resetSubmitAssessment);
  const submitting = useUnderwritingStore(selectSubmittingAssessment(caseId));
  // What was sent, for the receipt: the response is the case, which says the recommendation but not the evidence.
  const [sent, setSent] = useState<SubmitAssessmentFormValues | null>(null);

  const { register, handleSubmit, reset, control, formState: { errors } } = useForm<SubmitAssessmentFormValues>({
    ...VALIDATE_ON_TOUCH,
    resolver: zodResolver(submitAssessmentFormSchema),
    defaultValues: blankSubmitAssessmentForm(),
  });
  const typedScore = useWatch({ control, name: 'riskScore' }) ?? '';
  const scoreAlone = scoreRecommendation(typedScore);

  async function commit(values: SubmitAssessmentFormValues) {
    await submitAssessment(caseId, toApiRequest(values));
    if (useUnderwritingStore.getState().submittingAssessment[caseId]?.status === 'success') {
      setSent(values);
      reset(blankSubmitAssessmentForm());
    }
  }

  const title = isPostponed ? 'Submit further evidence' : 'Submit an assessment';
  const recorded = submitting.status === 'success' ? submitting.data : null;

  if (recorded && sent) {
    const outcome = recorded.recommendationOutcome ?? null;
    return (
      <Panel title={title}>
        <div className="p-4">
          <Receipt
            heading="Assessment recorded"
            lines={[
              { label: 'Type', value: humanizeStatus(sent.assessmentType) },
              { label: 'Risk score', value: sent.riskScore === '' ? 'Not scored' : sent.riskScore },
              ...(outcome
                ? [{
                    label: 'The case now recommends',
                    value: (
                      <>
                        <StatusBadge kind="underwritingDecision" value={outcome} />
                        {outcome === 'LOADED' && recorded.recommendationLoadingPercent != null
                          ? ` +${recorded.recommendationLoadingPercent}%` : null}
                      </>
                    ),
                  }]
                : []),
            ]}
            note="Next, the case is decided under Decide this case."
            onward={
              <Button type="button" size="sm" variant="outline"
                onClick={() => { setSent(null); resetSubmitAssessment(caseId); }}>
                Add another assessment
              </Button>
            }
          />
        </div>
      </Panel>
    );
  }

  return (
    <Panel emphasis title={title}>
      <form className="space-y-4 p-4" onSubmit={(e) => void handleSubmit(commit)(e)}>
        <FormField required label="Assessment type">
          <Select {...register('assessmentType')}>
            {ASSESSMENT_TYPES.map((t) => (
              <option key={t} value={t}>{humanizeStatus(t)}</option>
            ))}
          </Select>
        </FormField>

        <FormField required label="Findings" error={errors.findings?.message}>
          <Textarea className="min-h-20" {...register('findings')} />
        </FormField>

        <FormField label="Risk score" hint={`From 0 to 100: ${RISK_SCORE_BANDS}`} error={errors.riskScore?.message}>
          <Input className="w-32" inputMode="decimal" {...register('riskScore')} />
        </FormField>
        {scoreAlone && !errors.riskScore && (
          <p className="text-xs text-muted-foreground" aria-live="polite">
            On its own, this score recommends: <span className="text-foreground">{scoreAlone}</span>
          </p>
        )}

        {submitting.status === 'error' && submitting.error && <InlineError error={submitting.error} />}

        {/* No confirmation step: recording evidence is reversible -- submit another and the recommendation
            recomputes. The confirmation belongs on the decision, where the consequence lives. */}
        <Button type="submit" variant="primary" pending={submitting.status === 'loading'}>
          {isPostponed ? 'Submit further evidence' : 'Submit assessment'}
        </Button>
      </form>
    </Panel>
  );
}
