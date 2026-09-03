import { z } from 'zod';
import type { ManualIssueRequest, PremiumFrequency } from '@/api/types';
import { AMOUNT_PATTERN } from '@/lib/money';
import { CURRENCY_PATTERN, ISO_DATE_PATTERN, UUID_PATTERN } from '@/lib/patterns';
import { beneficiaryListSchema, toApiBeneficiaries, type BeneficiaryFormValues } from './beneficiaryForm';

/**
 * Zod schema for manual policy issuance, mirroring `ManualIssueRequestDto` and
 * `policy.infrastructure.MoneyDto` exactly.
 *
 * The one field this form does NOT expose: `underwritingCaseId`. It is
 * structurally required by the DTO, but `PolicyApiImpl.issuePolicy` never
 * validates it -- no existence check, nothing -- it is stored purely as an
 * audit-trail reference (confirmed by reading the method: the only real checks
 * are that `policyholderPartyId` resolves to a real party and `productVersionId`
 * resolves to a real snapshot). There is also no underwriting-case list/search
 * endpoint anywhere on this platform for a staff user to find a real one to
 * reference. Asking for a UUID nobody can obtain would be pure friction with no
 * realistic path to a correct value, so `toApiRequest` synthesizes one --
 * "manual issue" is explicitly the staff override path that bypasses the normal
 * underwriting pipeline in the first place.
 */

const amount = () =>
  z
    .string()
    .regex(AMOUNT_PATTERN, 'Must be a decimal amount like 2000000.00')
    .refine((v) => Number(v) >= 0.01, 'Must be at least 0.01');

const currency = () => z.string().regex(CURRENCY_PATTERN, 'Must be a 3-letter code like TZS');

const optionalUuid = () =>
  z
    .string()
    .trim()
    .refine((v) => v === '' || UUID_PATTERN.test(v), 'Not a valid id');

/** An optional whole number of months, kept as a string so an empty field stays empty. */
const months = (label: string) =>
  z
    .string()
    .trim()
    .refine((v) => v === '' || /^\d+$/.test(v), `${label} must be a whole number of months`)
    .refine((v) => v === '' || Number(v) >= 1, `${label} must be at least 1 month`);

export const policyIssueFormSchema = z.object({
  policyholderPartyId: z
    .string()
    .trim()
    .min(1, 'Policyholder party id is required')
    .regex(UUID_PATTERN, 'Not a valid party id'),
  productId: z.string().trim().min(1, 'Select a product'),
  productVersionId: z.string().trim().min(1, 'Select a product'),
  sumAssuredAmount: amount(),
  sumAssuredCurrency: currency(),
  premiumAmount: amount(),
  premiumCurrency: currency(),
  premiumFrequency: z.enum(['MONTHLY', 'QUARTERLY', 'ANNUALLY']),
  agentOfRecordId: optionalUuid(),
  reasonForManualIssue: z.string().trim().min(1, 'A reason is required -- it feeds the audit trail'),
  beneficiaries: beneficiaryListSchema,

  // The policy term. Optional, mirroring the DTO: whole life, an annuity and an
  // annually renewable group scheme genuinely have no term, so an empty value here
  // means "this product does not term", not "the user forgot".
  //
  // No maturity field. The aggregate derives it (Policy.applyTerm) and it is the only
  // place on the platform that computes it; `maturityPreview` below is display only.
  commencementDate: z
    .string()
    .trim()
    .refine((v) => v === '' || ISO_DATE_PATTERN.test(v), 'Not a valid date'),
  policyTermMonths: months('Policy term'),
  premiumPayingTermMonths: months('Premium-paying term'),
})
  .superRefine((values, ctx) => {
    // Mirrors policy_premium_paying_term_within_term and Policy.applyTerm. A
    // limited-payment policy pays for LESS time than it covers; longer is a data error,
    // and catching it here means a field message instead of a 400 from the domain.
    const term = values.policyTermMonths;
    const paying = values.premiumPayingTermMonths;
    if (term !== '' && paying !== '' && Number(paying) > Number(term)) {
      ctx.addIssue({
        code: 'custom',
        path: ['premiumPayingTermMonths'],
        message: 'Cannot be longer than the policy term',
      });
    }

    // A term with no start date cannot produce a maturity, so the record would carry a
    // duration nothing anchors. The reverse is fine: a commencement date without a term
    // is exactly how whole life is recorded.
    if (term !== '' && values.commencementDate === '') {
      ctx.addIssue({
        code: 'custom',
        path: ['commencementDate'],
        message: 'A policy term needs a commencement date to run from',
      });
    }
  });

/**
 * Input/Output split, same reason as BeneficiaryFormInput/Values: this schema
 * embeds `beneficiaryListSchema`, which uses `z.coerce.number()` for
 * `sharePercent`. useForm must be instantiated with both (see IssuePolicyPage)
 * so RHF's transformed-values support routes the coerced Output type to
 * onSubmit while the raw Input type governs what register()/watch() see.
 */
export type PolicyIssueFormValues = z.output<typeof policyIssueFormSchema>;
export type PolicyIssueFormInput = z.input<typeof policyIssueFormSchema>;

/**
 * The maturity date the current inputs imply, for showing on the form as the user types.
 *
 * **Display only, and never sent.** `Policy.applyTerm` is the single place maturity is
 * computed on this platform, and `policy_maturity_matches_term` enforces it in the
 * database; this exists so "240 months" is legible as a date before someone commits to
 * it, not to produce a value anything stores. That is also why it is a plain date
 * calculation and not the client-side arithmetic PLAN.md §4 forbids -- it touches no
 * money.
 *
 * Returns null when the inputs do not imply a maturity, which includes the perfectly
 * normal case of a product that does not term.
 */
export function maturityPreview(commencementDate: string, policyTermMonths: string): string | null {
  if (!ISO_DATE_PATTERN.test(commencementDate) || !/^\d+$/.test(policyTermMonths)) return null;
  const months = Number(policyTermMonths);
  if (months < 1) return null;

  const [year, month, day] = commencementDate.split('-').map(Number);
  // Mirrors java.time.LocalDate.plusMonths: land on the same day-of-month, clamped to
  // the last day of the target month when it is shorter. A naive Date(+months) rolls 31
  // Jan into 3 Mar instead, which would show the user a date the backend disagrees with.
  const target = new Date(Date.UTC(year!, month! - 1 + months, 1));
  const lastDay = new Date(Date.UTC(target.getUTCFullYear(), target.getUTCMonth() + 1, 0)).getUTCDate();
  target.setUTCDate(Math.min(day!, lastDay));
  return target.toISOString().slice(0, 10);
}

export function blankPolicyIssueForm(): PolicyIssueFormInput {
  return {
    policyholderPartyId: '',
    productId: '',
    productVersionId: '',
    sumAssuredAmount: '',
    sumAssuredCurrency: 'TZS',
    premiumAmount: '',
    premiumCurrency: 'TZS',
    premiumFrequency: 'MONTHLY' as PremiumFrequency,
    agentOfRecordId: '',
    reasonForManualIssue: '',
    beneficiaries: [],
    // Blank, not today's date. A commencement date is a contract term, and prefilling
    // one would have the form assert something nobody chose -- unlike the nationality
    // default in Build 1, where TZ is a demographic fact the user can see and correct.
    commencementDate: '',
    policyTermMonths: '',
    premiumPayingTermMonths: '',
  };
}

/** Convert validated form values into exactly what `POST /policies/manual-issue` expects. */
export function toApiRequest(values: PolicyIssueFormValues): ManualIssueRequest {
  return {
    // See the module doc above: never validated server-side, synthesized here.
    underwritingCaseId: crypto.randomUUID(),
    policyholderPartyId: values.policyholderPartyId.trim(),
    productVersionId: values.productVersionId,
    sumAssured: { amount: values.sumAssuredAmount, currencyCode: values.sumAssuredCurrency },
    premiumAmount: { amount: values.premiumAmount, currencyCode: values.premiumCurrency },
    premiumFrequency: values.premiumFrequency,
    // ManualIssueRequestDto's own javadoc: the JSON key must be present but its
    // value may be null (a direct/online channel with no agent) -- an empty
    // string is a different, wrong contract from an explicit null.
    agentOfRecordId: values.agentOfRecordId.trim() || null,
    reasonForManualIssue: values.reasonForManualIssue.trim(),
    beneficiaries: toApiBeneficiaries({ beneficiaries: values.beneficiaries } as BeneficiaryFormValues),
    // Omitted when blank rather than sent as null or 0. A product that does not term
    // has no term; sending 0 would trip policy_term_positive, and sending null would
    // claim the question was asked and answered.
    ...(values.commencementDate && { commencementDate: values.commencementDate }),
    ...(values.policyTermMonths && { policyTermMonths: Number(values.policyTermMonths) }),
    ...(values.premiumPayingTermMonths && {
      premiumPayingTermMonths: Number(values.premiumPayingTermMonths),
    }),
  };
}
