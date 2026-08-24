import { z } from 'zod';
import type { SettlementDecisionRequest } from '@/api/types';
import { AMOUNT_PATTERN } from '@/lib/money';
import { CURRENCY_PATTERN } from '@/lib/patterns';

/**
 * Zod schema for `POST /claims/{claimId}/settlement-decision`, transcribed from
 * `ClaimsApiImpl.decideSettlement` and `SettlementDecisionRequestDto` -- the spec
 * declares this request body inline with no cross-field rule, so the real
 * "approvedAmount/payeeRef required only when approved" constraint lives only in
 * the Java method, not in any schema. A `z.discriminatedUnion` on `approved`
 * makes that constraint structural instead of an ad hoc `superRefine`: the
 * approve branch's fields simply don't exist on the reject branch.
 */

const approveSchema = z.object({
  approved: z.literal(true),
  approvedAmount: z
    .string()
    .regex(AMOUNT_PATTERN, 'Must be a decimal amount like 1500000.00')
    .refine((v) => Number(v) >= 0.01, 'Must be at least 0.01'),
  approvedCurrency: z.string().regex(CURRENCY_PATTERN, 'Must be a 3-letter code like TZS'),
  // Claim.approve() itself has no @NotBlank annotation to mirror (it is a
  // domain object, not a DTO) -- ClaimsApiImpl.decideSettlement's own explicit
  // check IS the rule: "A payee reference is required to approve a claim".
  payeeRef: z.string().trim().min(1, 'A payee reference is required to approve'),
});

const rejectSchema = z.object({
  approved: z.literal(false),
  // No backend validation at all on rejectionReason -- genuinely optional.
  rejectionReason: z.string().trim(),
});

export const settlementDecisionFormSchema = z.discriminatedUnion('approved', [
  approveSchema,
  rejectSchema,
]);

export type SettlementDecisionFormValues = z.infer<typeof settlementDecisionFormSchema>;

export function blankApproveDecision(): SettlementDecisionFormValues {
  return { approved: true, approvedAmount: '', approvedCurrency: 'TZS', payeeRef: '' };
}

export function blankRejectDecision(): SettlementDecisionFormValues {
  return { approved: false, rejectionReason: '' };
}

export function toApiRequest(values: SettlementDecisionFormValues): SettlementDecisionRequest {
  if (values.approved) {
    return {
      approved: true,
      approvedAmount: { amount: values.approvedAmount, currencyCode: values.approvedCurrency },
      payeeRef: values.payeeRef.trim(),
      rejectionReason: null,
    };
  }
  // approvedAmount/payeeRef are OMITTED, not set to undefined: `exactOptionalPropertyTypes`
  // treats a present-but-undefined key differently from an absent one, and an
  // absent key is what "not meaningful on this branch" actually means here.
  return {
    approved: false,
    rejectionReason: values.rejectionReason.trim() || null,
    payeeRef: null,
  };
}
