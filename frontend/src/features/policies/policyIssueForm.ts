import { z } from 'zod';
import type { ManualIssueRequest, PremiumFrequency } from '@/api/types';
import { AMOUNT_PATTERN } from '@/lib/money';
import { UUID_PATTERN } from '@/lib/patterns';
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

const CURRENCY_PATTERN = /^[A-Z]{3}$/;
const currency = () => z.string().regex(CURRENCY_PATTERN, 'Must be a 3-letter code like TZS');

const optionalUuid = () =>
  z
    .string()
    .trim()
    .refine((v) => v === '' || UUID_PATTERN.test(v), 'Not a valid id');

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
  };
}
