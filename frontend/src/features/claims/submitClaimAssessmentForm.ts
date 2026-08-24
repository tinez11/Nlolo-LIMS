import { z } from 'zod';
import type { SubmitClaimAssessmentRequest } from '@/api/types';
import { AMOUNT_PATTERN } from '@/lib/money';
import { CURRENCY_PATTERN } from '@/lib/patterns';

/** Zod schema for `POST /claims/{claimId}/assessments`, mirroring
 *  `SubmitClaimAssessmentRequestDto` exactly. */

const amount = () =>
  z
    .string()
    .regex(AMOUNT_PATTERN, 'Must be a decimal amount like 1500000.00')
    .refine((v) => Number(v) >= 0.01, 'Must be at least 0.01');

const currency = () => z.string().regex(CURRENCY_PATTERN, 'Must be a 3-letter code like TZS');

export const submitClaimAssessmentFormSchema = z.object({
  findings: z.string().trim().min(1, 'Findings are required'),
  recommendedAmount: amount(),
  recommendedCurrency: currency(),
  fraudIndicator: z.boolean(),
});

export type SubmitClaimAssessmentFormValues = z.infer<typeof submitClaimAssessmentFormSchema>;

export function blankSubmitClaimAssessmentForm(): SubmitClaimAssessmentFormValues {
  return { findings: '', recommendedAmount: '', recommendedCurrency: 'TZS', fraudIndicator: false };
}

export function toApiRequest(values: SubmitClaimAssessmentFormValues): SubmitClaimAssessmentRequest {
  return {
    findings: values.findings.trim(),
    recommendedAmount: { amount: values.recommendedAmount, currencyCode: values.recommendedCurrency },
    fraudIndicator: values.fraudIndicator,
  };
}
