import { z } from 'zod';
import type { OpenCaseRequest } from '@/api/types';
import { AMOUNT_PATTERN } from '@/lib/money';
import { CURRENCY_PATTERN, UUID_PATTERN } from '@/lib/patterns';

/** Zod schema for `POST /underwriting/cases`, mirroring `OpenCaseRequest` exactly. */
export const openCaseFormSchema = z.object({
  applicantPartyId: z
    .string()
    .trim()
    .min(1, 'Applicant party id is required')
    .regex(UUID_PATTERN, 'Not a valid party id'),
  productId: z.string().trim().min(1, 'Select a product'),
  productVersionId: z.string().trim().min(1, 'Select a product'),
  sumAssuredAmount: z
    .string()
    .regex(AMOUNT_PATTERN, 'Must be a decimal amount like 1500000.00')
    .refine((v) => Number(v) >= 0.01, 'Must be at least 0.01'),
  sumAssuredCurrency: z.string().regex(CURRENCY_PATTERN, 'Must be a 3-letter code like TZS'),
  /**
   * Who sold it. Optional — a self-service application is a genuine direct sale — but it is
   * what makes commission accrue when the decision auto-issues a policy: distribution skips
   * accrual entirely for a policy with no agent of record, which is why no commission had
   * ever accrued on the automatic path.
   */
  agentOfRecordId: z
    .string()
    .trim()
    .refine((v) => v === '' || UUID_PATTERN.test(v), 'Not a valid agent id'),
});

export type OpenCaseFormValues = z.infer<typeof openCaseFormSchema>;

export function blankOpenCaseForm(): OpenCaseFormValues {
  return {
    applicantPartyId: '',
    productId: '',
    productVersionId: '',
    sumAssuredAmount: '',
    sumAssuredCurrency: 'TZS',
    agentOfRecordId: '',
  };
}

export function toApiRequest(values: OpenCaseFormValues): OpenCaseRequest {
  return {
    applicantPartyId: values.applicantPartyId.trim(),
    productId: values.productId,
    productVersionId: values.productVersionId,
    sumAssured: { amount: values.sumAssuredAmount, currencyCode: values.sumAssuredCurrency },
    // Omitted entirely when blank rather than sent as '': the field is a nullable uuid, and
    // an empty string is neither a uuid nor an absence the backend would accept.
    ...(values.agentOfRecordId ? { agentOfRecordId: values.agentOfRecordId } : {}),
  };
}
