import { z } from 'zod';
import type { Money, SubmitClaimAssessmentRequest } from '@/api/types';
import { AMOUNT_PATTERN, compareAmounts, formatMoney } from '@/lib/money';
import { CURRENCY_PATTERN } from '@/lib/patterns';

/** Zod schema for `POST /claims/{claimId}/assessments`, mirroring
 *  `SubmitClaimAssessmentRequestDto` exactly. */

const currency = () => z.string().regex(CURRENCY_PATTERN, 'Must be a 3-letter code like TZS');

/**
 * Bounded by the same ceiling the eventual approval is.
 *
 * Mirrors `ClaimsApiImpl.submitAssessment`, which refuses a recommendation above the cover with a
 * 422. Only the APPROVAL used to be bounded, so an assessor could record any figure and the
 * refusal landed later, on a different person, in a different session -- a manager looking at a
 * colleague's recommendation they could not act on. This check just says so before the round
 * trip. `null` keeps the plain shape checks and leaves the server the authority.
 */
export function submitClaimAssessmentSchema(claimableCover: Money | null) {
  return z.object({
    findings: z.string().trim().min(1, 'Findings are required'),
    recommendedAmount: z
      .string()
      .regex(AMOUNT_PATTERN, 'Must be a decimal amount like 1500000.00')
      .refine((v) => Number(v) >= 0.01, 'Must be at least 0.01')
      .refine((v) => claimableCover === null || compareAmounts(v, claimableCover.amount) <= 0, {
        message: claimableCover
          ? `More than this claim is covered for — the most it can pay is ${formatMoney(claimableCover)}`
          : 'More than this claim is covered for',
      }),
    recommendedCurrency: currency(),
    fraudIndicator: z.boolean(),
  });
}

/** The unbounded schema, kept as the shape consumers type against. */
export const submitClaimAssessmentFormSchema = submitClaimAssessmentSchema(null);

export type SubmitClaimAssessmentFormValues = z.infer<typeof submitClaimAssessmentFormSchema>;

/**
 * Starts at the full cover when it is known.
 *
 * A death claim on credit life pays the outstanding balance exactly, so the full cover is the
 * right opening position rather than a blank an assessor fills by copying a number off another
 * screen. Anyone recommending less types it; the figure is a starting point, not a decision.
 */
export function blankSubmitClaimAssessmentForm(
  claimableCover?: Money | null,
): SubmitClaimAssessmentFormValues {
  return {
    findings: '',
    recommendedAmount: claimableCover?.amount ?? '',
    recommendedCurrency: claimableCover?.currencyCode ?? 'TZS',
    fraudIndicator: false,
  };
}

export function toApiRequest(values: SubmitClaimAssessmentFormValues): SubmitClaimAssessmentRequest {
  return {
    findings: values.findings.trim(),
    recommendedAmount: { amount: values.recommendedAmount, currencyCode: values.recommendedCurrency },
    fraudIndicator: values.fraudIndicator,
  };
}
