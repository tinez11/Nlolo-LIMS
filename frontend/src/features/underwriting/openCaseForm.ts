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
});

export type OpenCaseFormValues = z.infer<typeof openCaseFormSchema>;

export function blankOpenCaseForm(): OpenCaseFormValues {
  return {
    applicantPartyId: '',
    productId: '',
    productVersionId: '',
    sumAssuredAmount: '',
    sumAssuredCurrency: 'TZS',
  };
}

export function toApiRequest(values: OpenCaseFormValues): OpenCaseRequest {
  return {
    applicantPartyId: values.applicantPartyId.trim(),
    productId: values.productId,
    productVersionId: values.productVersionId,
    sumAssured: { amount: values.sumAssuredAmount, currencyCode: values.sumAssuredCurrency },
  };
}
