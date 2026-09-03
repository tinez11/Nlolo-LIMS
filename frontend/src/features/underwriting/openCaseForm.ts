import { z } from 'zod';
import type { OpenCaseRequest } from '@/api/types';
import { AMOUNT_PATTERN } from '@/lib/money';
import { CURRENCY_PATTERN, ISO_DATE_PATTERN, UUID_PATTERN } from '@/lib/patterns';

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

  /**
   * Whose life is insured, when that is not the applicant.
   *
   * Blank means self-insured — the common case, and the only one the model could express
   * before this field existed. Most life business is not self-insured though: a parent
   * insures a child, an employer its staff. Both group business and credit life are
   * structurally impossible without the distinction.
   */
  lifeAssuredPartyId: z
    .string()
    .trim()
    .refine((v) => v === '' || UUID_PATTERN.test(v), 'Not a valid party id'),

  branch: z.string().trim().max(100, 'Cannot exceed 100 characters'),

  /** Free text: the platform does not own this vocabulary until TIRA publishes one. */
  sourceOfBusiness: z.string().trim().max(60, 'Cannot exceed 60 characters'),

  proposedCommencementDate: z
    .string()
    .trim()
    .refine((v) => v === '' || ISO_DATE_PATTERN.test(v), 'Not a valid date'),
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
    lifeAssuredPartyId: '',
    branch: '',
    sourceOfBusiness: '',
    proposedCommencementDate: '',
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
    // Same omit-when-blank rule. A blank life assured is not a null to send: it means
    // self-insured, and the backend resolves that to the applicant rather than storing a
    // null every later reader would have to interpret.
    ...(values.lifeAssuredPartyId ? { lifeAssuredPartyId: values.lifeAssuredPartyId } : {}),
    ...(values.branch ? { branch: values.branch } : {}),
    ...(values.sourceOfBusiness ? { sourceOfBusiness: values.sourceOfBusiness } : {}),
    ...(values.proposedCommencementDate
      ? { proposedCommencementDate: values.proposedCommencementDate }
      : {}),
  };
}
