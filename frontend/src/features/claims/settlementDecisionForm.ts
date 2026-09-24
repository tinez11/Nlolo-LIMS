import { z } from 'zod';
import type { Money, SettlementDecisionRequest } from '@/api/types';
import { AMOUNT_PATTERN, compareAmounts, formatMoney } from '@/lib/money';
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

const rejectSchema = z.object({
  approved: z.literal(false),
  // No backend validation at all on rejectionReason -- genuinely optional.
  rejectionReason: z.string().trim(),
});

/**
 * The ceiling is a PARAMETER, so the schema is built per claim rather than being a module
 * constant.
 *
 * `Claim.approve` refuses an amount above what the claim is covered for, and until this existed
 * the only way to learn that number was to exceed it and read the 422 -- which quoted a raw
 * `800000.00` back at somebody who had been shown a `1500000.00` placeholder and nothing else.
 *
 * Passing `null` (the cover has not loaded, or the cover read itself failed) keeps the old
 * behaviour: amount-shape checks only, and the server stays the authority. It must never be
 * treated as "no limit was breached".
 */
export function settlementDecisionSchema(claimableCover: Money | null) {
  const approveSchema = z.object({
    approved: z.literal(true),
    approvedAmount: z
      .string()
      .regex(AMOUNT_PATTERN, 'Must be a decimal amount like 1500000.00')
      .refine((v) => Number(v) >= 0.01, 'Must be at least 0.01')
      .refine(
        (v) => claimableCover === null || compareAmounts(v, claimableCover.amount) <= 0,
        // The server's own sentence, in money a person reads. "exceeds the 800000.00 this claim
        // is covered for" is correct and unreadable; the figure it names is the one thing the
        // reader needs, so it is formatted rather than dumped.
        {
          message: claimableCover
            ? `More than this claim is covered for — the most it can pay is ${formatMoney(claimableCover)}`
            : 'More than this claim is covered for',
        },
      ),
    approvedCurrency: z.string().regex(CURRENCY_PATTERN, 'Must be a 3-letter code like TZS'),
    // Claim.approve() itself has no @NotBlank annotation to mirror (it is a
    // domain object, not a DTO) -- ClaimsApiImpl.decideSettlement's own explicit
    // check IS the rule: "A payee reference is required to approve a claim".
    payeeRef: z.string().trim().min(1, 'A payee reference is required to approve'),
  });

  return z.discriminatedUnion('approved', [approveSchema, rejectSchema]);
}

/** The unbounded schema, kept as the shape every other consumer types against. */
export const settlementDecisionFormSchema = settlementDecisionSchema(null);

export type SettlementDecisionFormValues = z.infer<typeof settlementDecisionFormSchema>;

/**
 * The starting amount, and the whole point of the change: it is no longer blank.
 *
 * Order matters and is a business rule, not a convenience. The ASSESSOR'S RECOMMENDATION comes
 * first -- a human looked at the evidence and wrote a figure, and a manager who silently pays
 * the full cover instead has overruled them without noticing. The cover is the fallback for a
 * claim that has no assessment at all, which on this platform means a MATURITY claim
 * auto-approving straight from REGISTERED.
 *
 * Neither is locked. "Change it if there is a new finding" is the actual workflow; what was
 * wrong was starting from nothing and being corrected by a 422.
 *
 * A recommendation ABOVE the cover is not a starting point: the form would open already refusing
 * its own value. The backend now refuses such a recommendation when it is written, but ones
 * recorded before that exist, so the form starts at the cover instead -- the most that can be
 * paid -- and the panel says why (`recommendationExceedsCover`).
 */
export function blankApproveDecision(
  recommended?: Money | null,
  claimableCover?: Money | null,
): Extract<SettlementDecisionFormValues, { approved: true }> {
  const usable = recommended && !recommendationExceedsCover(recommended, claimableCover)
    ? recommended
    : null;
  const start = usable ?? claimableCover ?? null;
  return {
    approved: true,
    approvedAmount: start?.amount ?? '',
    approvedCurrency: start?.currencyCode ?? 'TZS',
    payeeRef: '',
  };
}

/**
 * True only when BOTH figures are known and the recommendation is strictly above the cover.
 * Unknown cover is not "exceeds" -- nothing can be claimed about a ceiling nobody has read.
 */
export function recommendationExceedsCover(
  recommended: Money | null | undefined,
  claimableCover: Money | null | undefined,
): boolean {
  if (!recommended || !claimableCover) return false;
  return compareAmounts(recommended.amount, claimableCover.amount) > 0;
}

export function blankRejectDecision(): SettlementDecisionFormValues {
  return { approved: false, rejectionReason: '' };
}

/**
 * What the form becomes when the decision control is switched -- or `null` to leave it as is.
 *
 * Choosing the branch already selected changes NOTHING. It used to reset the form, so clicking
 * "Approve" while approving wiped the prefilled amount and a typed payee reference.
 *
 * Switching to approve starts from the same figures the form opened with. It used to call
 * `blankApproveDecision()` with no arguments, so Reject → Approve left the amount blank -- and
 * nothing refilled it, because the prefill effect only reruns when the figures themselves change.
 */
export function switchDecision(
  to: 'approve' | 'reject',
  currentlyApproved: boolean,
  recommended: Money | null,
  claimableCover: Money | null,
): SettlementDecisionFormValues | null {
  if ((to === 'approve') === currentlyApproved) return null;
  return to === 'approve' ? blankApproveDecision(recommended, claimableCover) : blankRejectDecision();
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
